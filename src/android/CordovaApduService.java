// Cordova HCE Plugin
// (c) 2015 Don Coleman

package com.megster.cordova.hce;

import android.content.Intent;
import android.content.SharedPreferences;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CordovaApduService extends HostApduService {

    private static final String TAG = "CordovaApduService";

    // tight binding between the service and plugin
    // future versions could use bind
    private static HCEPlugin hcePlugin;

    public static HCEPlugin getHcePlugin() {
        return hcePlugin;
    }

    private static CordovaApduService cordovaApduService;

    static void setHCEPlugin(HCEPlugin _hcePlugin) {
        hcePlugin = _hcePlugin;
    }


    private static final byte[] ISO7816_UNKNOWN_ERROR_RESPONSE = {
            (byte)0x6F, (byte)0x00
    };

    private static final byte[] PPSE_APDU_SELECT = {
            (byte)0x00, // CLA (class of command)
            (byte)0xA4, // INS (instruction); A4 = select
            (byte)0x04, // P1  (parameter 1)  (0x04: select by name)
            (byte)0x00, // P2  (parameter 2)
            (byte)0x0E, // LC  (length of data)  14 (0x0E) = length("2PAY.SYS.DDF01")
            // 2PAY.SYS.DDF01 (ASCII values of characters used):
            // This value requests the card or payment device to list the application
            // identifiers (AIDs) it supports in the response:
            '2', 'P', 'A', 'Y', '.', 'S', 'Y', 'S', '.', 'D', 'D', 'F', '0', '1',
            (byte)0x00 // LE   (max length of expected result, 0 implies 256)
    };

    private static final byte[] PPSE_APDU_SELECT_RESP = {
            (byte)0x6F,  // FCI Template
            (byte)0x23,  // length = 35
            (byte)0x84,  // DF Name
            (byte)0x0E,  // length("2PAY.SYS.DDF01")
            // Data (ASCII values of characters used):
            '2', 'P', 'A', 'Y', '.', 'S', 'Y', 'S', '.', 'D', 'D', 'F', '0', '1',
            (byte)0xA5, // FCI Proprietary Template
            (byte)0x11, // length = 17
            (byte)0xBF, // FCI Issuer Discretionary Data
            (byte)0x0C, // length = 12
            (byte)0x0E,
            (byte)0x61, // Directory Entry
            (byte)0x0C, // Entry length = 12
            (byte)0x4F, // ADF Name
            (byte)0x07, // ADF Length = 7
            // Tell the POS (point of sale terminal) that we support the standard
            // Visa credit or debit applet: A0000000031010
            // Visa's RID (Registered application provider IDentifier) is 5 bytes:
            (byte)0xA0, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03,
            // PIX (Proprietary application Identifier eXtension) is the last 2 bytes.
            // 10 10 (means visa credit or debit)
            (byte)0x10, (byte)0x10,
            (byte)0x87,  // Application Priority Indicator
            (byte)0x01,  // length = 1
            (byte)0x01,
            (byte) 0x90, // SW1  (90 00 = Success)
            (byte) 0x00  // SW2
    };

    /*
     *  MSD (Magnetic Stripe Data)
     */
    private static final byte[] VISA_MSD_SELECT = {
            (byte)0x00,  // CLA
            (byte)0xa4,  // INS
            (byte)0x04,  // P1
            (byte)0x00,  // P2
            (byte)0x07,  // LC (data length = 7)
            // POS is selecting the AID (Visa debit or credit) that we specified in the PPSE
            // response:
            (byte)0xA0, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03, (byte)0x10, (byte)0x10,
            (byte)0x00   // LE
    };


    private static final byte[] VISA_MSD_SELECT_RESPONSE = {
            (byte) 0x6F,  // File Control Information (FCI) Template
            (byte) 0x1E,  // length = 30 (0x1E)
            (byte) 0x84,  // Dedicated File (DF) Name
            (byte) 0x07,  // DF length = 7

            // A0000000031010  (Visa debit or credit AID)
            (byte)0xA0, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03, (byte)0x10, (byte)0x10,

            (byte) 0xA5,  // File Control Information (FCI) Proprietary Template
            (byte) 0x13,  // length = 19 (0x13)
            (byte) 0x50,  // Application Label
            (byte) 0x0B,  // length
            'V', 'I', 'S', 'A', ' ', 'C', 'R', 'E', 'D', 'I', 'T',
            (byte) 0x9F, (byte) 0x38,  // Processing Options Data Object List (PDOL)
            (byte) 0x03,  // length
            (byte) 0x9F, (byte) 0x66, (byte) 0x02, // PDOL value (Does this request terminal type?)
            (byte) 0x90,  // SW1
            (byte) 0x00   // SW2
    };


    /*
     *  GPO (Get Processing Options) command
     */
    private static final byte[] GPO_COMMAND = {
            (byte) 0x80,  // CLA
            (byte) 0xA8,  // INS
            (byte) 0x00,  // P1
            (byte) 0x00,  // P2
            (byte) 0x04,  // LC (length)
            // data
            (byte) 0x83,  // tag
            (byte) 0x02,  // length
            (byte) 0x80,    //  { These 2 bytes can vary, so we'll only        }
            (byte) 0x00,    //  { compare the header of this GPO command below }
            (byte) 0x00   // Le
    };


    /*
     *  The data in the request can vary, but it won't affect our response. This method
     *  checks the initial 4 bytes of an APDU to see if it's a GPO command.
     */
    private boolean isGpoCommand(byte[] apdu) {
        return (apdu.length > 4 &&
                apdu[0] == GPO_COMMAND[0] &&
                apdu[1] == GPO_COMMAND[1] &&
                apdu[2] == GPO_COMMAND[2] &&
                apdu[3] == GPO_COMMAND[3]
        );
    }


    /*
     *  SwipeYours only emulates Visa MSD, so our response is not dependant on the GPO command
     *  data.
     */
    private static final byte[] GPO_COMMAND_RESPONSE = {
            (byte) 0x80,
            (byte) 0x06,  // length
            (byte) 0x00,
            (byte) 0x80,
            (byte) 0x08,
            (byte) 0x01,
            (byte) 0x01,
            (byte) 0x00,
            (byte) 0x90,  // SW1
            (byte) 0x00   // SW2
    };


    private static final byte[] READ_REC_COMMAND = {
            (byte) 0x00,  // CLA
            (byte) 0xB2,  // INS
            (byte) 0x01,  // P1
            (byte) 0x0C,  // P2
            (byte) 0x00   // length
    };


    private static final Pattern TRACK_2_PATTERN = Pattern.compile(".*;(\\d{12,19}=\\d{1,128})\\?.*");

    /*
         *  Unlike the upper case commands above, the Read REC response changes depending on the track 2
         *  portion of the user's magnetic stripe data.
         */
    private static byte[] readRecResponse = {};

    public static byte[] getReadRecResponse() {
        return readRecResponse;
    }

    public static void setReadRecResponse(byte[] readRecResponse) {
        CordovaApduService.readRecResponse = readRecResponse;
    }

    public static void configureReadRecResponse(String swipeData) {
        Matcher matcher = TRACK_2_PATTERN.matcher(swipeData);
        if (matcher.matches()) {

            String track2EquivData = matcher.group(1);
            // convert the track 2 data into the required byte representation
            track2EquivData = track2EquivData.replace('=', 'D');
            if (track2EquivData.length() % 2 != 0) {
                // add an 'F' to make the hex string a whole number of bytes wide
                track2EquivData += "F";
            }

            // Each binary byte is represented by 2 4-bit hex characters
            int track2EquivByteLen = track2EquivData.length()/2;

            readRecResponse = new byte[6 + track2EquivByteLen];

            ByteBuffer bb = ByteBuffer.wrap(readRecResponse);
            bb.put((byte) 0x70);                            // EMV Record Template tag
            bb.put((byte) (track2EquivByteLen + 2));        // Length with track 2 tag
            bb.put((byte) 0x57);                                // Track 2 Equivalent Data tag
            bb.put((byte)track2EquivByteLen);                   // Track 2 data length
            bb.put(Util.hexToByteArray(track2EquivData));           // Track 2 equivalent data
            bb.put((byte) 0x90);                            // SW1
            bb.put((byte) 0x00);                            // SW2
        } else {
        }

    }



    static boolean sendResponse(byte[] data) {
        if (cordovaApduService != null) {
            cordovaApduService.sendResponseApdu(data);
            return true;
        } else {
            return false;
        }
    }

/*    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        Log.i(TAG, "Received APDU: " + ByteArrayToHexString(commandApdu));

        // save a reference in static variable (hack)
        cordovaApduService = this;

        if (hcePlugin != null) {
            hcePlugin.sendCommand(commandApdu);
        } else {
            Log.e(TAG, "No reference to HCE Plugin.");
        }

        // return null since JavaScript code will send the response
        return null;
    }*/

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle bundle) {

        Log.i(TAG, "Received APDU: " + ByteArrayToHexString(commandApdu));

        // save a reference in static variable (hack)
        cordovaApduService = this;

        String inboundApduDescription;
        byte[] responseApdu;

        if (Arrays.equals(PPSE_APDU_SELECT, commandApdu)) {
            inboundApduDescription = "Received PPSE select: ";
            responseApdu = PPSE_APDU_SELECT_RESP;
        } else if (Arrays.equals(VISA_MSD_SELECT, commandApdu)) {
            inboundApduDescription =  "Received Visa-MSD select: ";
            responseApdu =  VISA_MSD_SELECT_RESPONSE;
        } else if (isGpoCommand(commandApdu)) {
            inboundApduDescription =  "Received GPO (get processing options): ";
            responseApdu =  GPO_COMMAND_RESPONSE;
        } else if (Arrays.equals(READ_REC_COMMAND, commandApdu)) {
            inboundApduDescription = "Received READ REC: ";
            responseApdu = readRecResponse;
        } else {
            inboundApduDescription = "Received Unhandled APDU: ";
            responseApdu = ISO7816_UNKNOWN_ERROR_RESPONSE;
        }



        String message = inboundApduDescription + Util.byteArrayToHex(commandApdu) +
                " / Response: " + Util.byteArrayToHex(responseApdu);

        if (hcePlugin != null)
            hcePlugin.onNotificationCallback.success(message);

        Log.i(TAG, message);

        return responseApdu;
    }

/*    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        Log.i(TAG, "onSharedPreferenceChanged: key=" + key);
        if (Constants.SWIPE_DATA_PREF_KEY.equals(key)) {
            String swipeData = prefs.getString(Constants.SWIPE_DATA_PREF_KEY, Constants.DEFAULT_SWIPE_DATA);
            configureReadRecResponse(swipeData);
        }
    }*/

    /**
     * Called if the connection to the NFC card is lost, in order to let the application know the
     * cause for the disconnection (either a lost link, or another AID being selected by the
     * reader).
     *
     * @param reason Either DEACTIVATION_LINK_LOSS or DEACTIVATION_DESELECTED
     */
    @Override
    public void onDeactivated(int reason) {

        if (hcePlugin != null) {
            hcePlugin.deactivated(reason);
        } else {
            Log.e(TAG, "No reference to HCE Plugin.");
        }

    }

    /**
     * Utility method to convert a byte array to a hexadecimal string.
     *
     * @param bytes Bytes to convert
     * @return String, containing hexadecimal representation.
     */
    public static String ByteArrayToHexString(byte[] bytes) {
        final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2]; // Each byte has two hex characters (nibbles)
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF; // Cast bytes[j] to int, treating as unsigned value
            hexChars[j * 2] = hexArray[v >>> 4]; // Select hex character from upper nibble
            hexChars[j * 2 + 1] = hexArray[v & 0x0F]; // Select hex character from lower nibble
        }
        return new String(hexChars);
    }

    public void onCreate() {
        super.onCreate();

        // Attempt to get swipe data that SetCardActivity saved as a shared preference,
        // otherwise use the default no-balance prepaid visa configured into the app.
        /*SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String swipeData = prefs.getString(Constants.SWIPE_DATA_PREF_KEY, Constants.DEFAULT_SWIPE_DATA);
        configureReadRecResponse(swipeData);
        prefs.registerOnSharedPreferenceChangeListener(this);*/
    }

}
