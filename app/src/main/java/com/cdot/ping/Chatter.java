package com.cdot.ping;

import android.os.Handler;

/**
 * Abstract interface to a data source.
 */
abstract class Chatter {
    // Constants which indicate the current connection state
    static final int STATE_NONE = 0;            // we're doing nothing, or getting connected
    static final int STATE_CONNECTED = 1;       // now connected to a remote device
    static final int STATE_DISCONNECTED = 2;    // Connection was lost

    static final String[] stateName = { "None", "Connected", "Disconnected" }; // debug trace only

    // Messages sent by this module to mListener
    static final int MESSAGE_STATE_CHANGE = 1;  // arg1 = one of the STATE_* constants below
    static final int MESSAGE_SONAR_DATA = 16;   // obj = SampleData

    Handler mListener; // Listener to messages sent by this interface

    Chatter(Handler listener) {
        mListener = listener;
    }

    /**
     * Called when the app is paused
     */
    abstract void onPause();

    /**
     *  Called when the app is resumed
     */
    abstract void onResume();

    /**
     * Called to send configuration data to the connected device
     */
    abstract void configure(int sensitivity, int noise, int range);

    /**
     * Called to connect to a device
     */
    abstract void connect(DeviceRecord dev);

    /**
     * Called to disconnect from a device
     */
    abstract void disconnect();

    /**
     * Called to stop any running background services
     */
    abstract void stopServices();
}