package com.cdot.ping;

import android.os.Handler;

import com.cdot.bluetooth.BluetoothService;

import java.util.Timer;
import java.util.TimerTask;

class DemoChat extends Chatter {
    private static final float[] demoDepth = {
            24.9f, 24.6f, 24.6f, 24.6f, 24.6f, 24.6f, 24.6f, 24.6f, 24.6f, 24.6f,
            24.6f, 24.3f, 24.3f, 24.3f, 24.3f, 24.3f, 24.3f, 24.3f, 24.3f, 24.0f,
            24.0f, 24.0f, 24.0f, 24.0f, 23.6f, 23.6f, 23.6f, 23.6f, 23.6f, 23.6f,
            23.3f, 23.3f, 23.3f, 23.3f, 23.0f, 23.0f, 23.0f, 23.0f, 23.0f, 22.7f,
            22.7f, 22.7f, 22.7f, 22.4f, 22.4f, 22.4f, 22.1f, 22.1f, 22.1f, 22.1f,
            22.1f, 21.8f, 21.8f, 21.8f, 21.8f, 21.8f, 21.5f, 21.5f, 21.5f, 21.5f,
            21.5f, 21.5f, 21.5f, 21.2f, 21.2f, 21.2f, 21.2f, 21.2f, 21.2f, 21.2f,
            21.2f, 21.2f, 21.2f, 21.5f, 21.5f, 21.5f, 21.5f, 21.5f, 21.5f, 21.5f,
            21.8f, 21.8f, 21.8f, 21.8f, 21.8f, 21.8f, 21.8f, 22.1f, 22.1f, 22.1f,
            22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f,
            22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f,
            22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f, 22.1f,
            21.8f, 21.8f, 21.8f, 21.8f, 21.8f, 21.5f, 21.5f, 21.5f, 21.5f, 21.5f,
            21.2f, 21.2f, 21.2f, 21.2f, 20.9f, 20.9f, 20.9f, 20.6f, 20.6f, 20.6f,
            20.6f, 20.6f, 20.6f, 20.6f, 20.6f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f,
            20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f,
            20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f, 20.3f,
            20.3f, 20.3f, 20.3f, 20.6f, 20.6f, 20.6f, 20.6f, 20.6f, 20.9f, 20.9f,
            20.9f, 20.9f, 20.9f, 20.9f, 21.2f, 21.2f, 21.2f, 21.2f, 21.2f, 21.5f,
            21.5f, 21.5f, 21.5f, 21.8f, 21.8f, 21.8f, 21.8f, 22.1f, 22.1f, 22.1f,
            22.4f, 22.4f, 22.4f, 22.4f, 22.7f, 22.7f, 22.7f, 22.7f, 22.7f, 23.0f,
            23.0f, 23.0f, 23.0f, 23.3f, 23.3f, 23.3f, 23.6f, 23.6f, 23.6f, 24.0f,
            24.0f, 24.0f, 24.0f, 24.0f, 24.3f, 24.3f, 24.3f, 24.3f, 24.3f, 24.3f,
            24.6f, 24.6f, 24.6f, 24.6f, 24.6f, 24.6f, 24.6f, 24.6f, 24.9f, 24.9f,
            24.9f, 24.9f, 24.9f, 24.9f, 24.9f, 24.9f, 24.9f, 24.9f, 24.9f, 24.9f};
    private static final float[] demoStrength = {
            47.5f, 66.3f, 88.2f, 24.3f, 46.6f, 41.2f, 16.9f, 27.9f, 60.1f, 66.3f,
            8f, 95.3f, 70f, 17.7f, 19.5f, 47.2f, 10.4f, 71.1f, 8f, 21.4f,
            32.9f, 9.2f, 52.5f, 51.9f, 75.6f, 75.5f, 0.2f, 32.5f, 14.1f, 97.7f,
            73.3f, 50.7f, 91.5f, 78.7f, 94.4f, 44f, 72.3f, 51.7f, 21.9f, 39f,
            34.4f, 19.3f, 64.5f, 101.1f, 43.8f, 71.4f, 3.1f, 51.9f, 60.7f, 16.3f,
            36.7f, 69.5f, 76.5f, 99.9f, 28.1f, 56.6f, 8.6f, 57.2f, 30.5f, 56f,
            6.7f, 16.2f, 3.9f, 7.2f, 74.8f, 63.1f, 104.4f, 33f, 65.7f, 68.7f,
            3.7f, 51.3f, 62.5f, 84.6f, 13.6f, 39.7f, 74.1f, 61f, 2.7f, 13.9f,
            7.8f, 100.9f, 51f, 73.3f, 27.6f, 41.7f, 89.9f, 42.9f, 12f, 15.6f,
            21.3f, 94.7f, 56.5f, 92.5f, 72.8f, 35f, 53.4f, 5.5f, 30.1f, 21.7f,
            56.4f, 48.2f, 105.2f, 39.3f, 22.7f, 7.3f, 61.3f, 49.7f, 99.6f, 100.5f,
            95.1f, 0.5f, 63.3f, 49.6f, 57.5f, 30f, 25.1f, 40.3f, 102.4f, 85.1f,
            5f, 14.5f, 14.5f, 92.1f, 58.6f, 79f, 93.6f, 72.9f, 10.2f, 77.7f,
            11.5f, 1.9f, 95.1f, 66.9f, 99.4f, 31.4f, 44.2f, 12.7f, 54.8f, 25.5f,
            90.5f, 27.2f, 33.6f, 38f, 48.7f, 78.4f, 61.2f, 98.6f, 90.6f, 31f,
            77f, 25.9f, 90.4f, 12.3f, 79};
    private static final float[] demoFishDepth = new float[477];
    private static final int[] demoFishType = new int[500];

    static {
        demoFishDepth[3] = 9.8f;
        demoFishType[3] = 4;

        demoFishDepth[75] = 13.8f;
        demoFishType[75] = 1;

        demoFishDepth[142] = 4;
        demoFishType[142] = 3;

        demoFishDepth[199] = 8.6f;
        demoFishType[199] = 4;

        demoFishDepth[274] = 10.7f;
        demoFishType[274] = 2;

        demoFishDepth[364] = 14.7f;
        demoFishType[364] = 4;

        demoFishDepth[400] = 8;
        demoFishType[400] = 3;

        demoFishDepth[474] = 9.8f;
        demoFishType[474] = 2;
    }

    private Timer mTimer = null;
    private TimerTask mTask = null;
    private int mTicker = 0;
    private boolean mConnected = false;

    DemoChat(Handler listener) {
        super(listener);
        mListener = listener;
    }

    // Called when the app is paused
    void onPause() {
        stopTimer();
        mListener.obtainMessage(MESSAGE_STATE_CHANGE, STATE_DISCONNECTED, BluetoothService.CONNECTION_LOST).sendToTarget();
    }

    // Called when the app is resumed
    void onResume() {
        if (mConnected)
            startTimer();
    }

    // Called to configure the connected device
    void configure(int sensitivity, int noise, int range) {
    }

    // Called to connect to a device
    void connect(DeviceRecord dev) {
        mListener.obtainMessage(MESSAGE_STATE_CHANGE, STATE_CONNECTED, -1).sendToTarget();
        mConnected = true;
        startTimer();
    }

    private void stopTimer() {
        if (mTimer != null)
            mTimer.cancel();
        if (mTask != null)
            mTask.cancel();
        mTimer = null;
        mTask = null;
    }

    private void startTimer() {
        mTimer = new Timer();
        mTask = new TimerTask() {
            public void run() {
                SonarData data = new SonarData();
                data.isLand = false;
                data.depth = demoDepth[mTicker % demoDepth.length];
                data.strength = (int) (demoStrength[mTicker % demoStrength.length]);
                data.fishDepth = demoFishDepth[mTicker % demoFishDepth.length];
                data.fishType = demoFishType[mTicker % demoFishType.length];
                data.battery = (int) (Math.random() * 6);
                data.temperature = (float) Math.random() * 35f;
                mTicker++;
                mListener.obtainMessage(Chatter.MESSAGE_SONAR_DATA, data).sendToTarget();
            }
        };
        mTimer.schedule(mTask, 250, 250);
    }

    // Called to stop any running background services
    void stopService() {
        stopTimer();
        mConnected = false;
    }
}