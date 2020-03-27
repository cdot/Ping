package com.cdot.ping.devices;

import android.content.Intent;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

public class DemoService extends DeviceService {
    private static final String TAG = "DemoService";

    private DeviceRecord mConnected = null;
    private String mDeviceName;
    private float mDepth = 10f, mStrength = 50f;
    private float mBattery = 6;
    private float mTemperature = 18;

    @Override
    public void onCreate() {
        super.onCreate();
        startTimer();
    }

    private Timer mTimer = null;

    private void startTimer() {
        mTimer = new Timer();
        TimerTask task = new TimerTask() {
            public void run() {
                if (mConnected == null)
                    return;
                mDepth += Math.random() - 0.5f;
                if (mDepth < 0) mDepth = 0;
                if (mDepth > 36) mDepth = 36;
                mStrength += Math.random() - 0.5f;
                if (mStrength < 0 || mStrength > 100) mStrength = 50;
                float fishDepth = 0, fishStrength = 0;
                if (Math.random() < 0.2) {
                    fishDepth = (float)(3f + Math.random() * (mDepth - 3));
                    fishStrength = 1 + (float)Math.floor(Math.random() * 4);
                }
                mBattery -= 0.01;
                mTemperature += (Math.random() - 0.5f);
                if (mTemperature < 0) mTemperature = 0;
                else if (mTemperature > 60) mTemperature = 60;
                mSampler.updateSampleData(false, mDepth, mStrength, fishDepth, fishStrength, (int)mBattery, mTemperature);
            }
        };
        mTimer.schedule(task, 0, 1000);
    }

    private void stopTimer() {
        if (mTimer != null)
            mTimer.cancel();
        mTimer = null;
    }

    public boolean connect(DeviceRecord device) {
        super.connect(device);
        stopTimer();
        mDeviceName = device.name;
        Log.d(TAG, "Connecting " + mDeviceName);
        mConnected = device;
        // Tell the world we are ready for action
        Intent intent = new Intent(ACTION_CONNECTED);
        intent.putExtra(DEVICE_ADDRESS, device.address);
        sendBroadcast(intent);
        startTimer();
        return true;
    }

    public void disconnect() {
        Log.d(TAG, "Disconnecting " + mDeviceName);
        stopTimer();
        if (mConnected != null) {
            Intent intent = new Intent(ACTION_DISCONNECTED);
            intent.putExtra(DEVICE_ADDRESS, mConnected.address);
            intent.putExtra(REASON, CONNECTION_LOST);
            sendBroadcast(intent);
        }
        mConnected = null;
    }

    public void configure(int sensitivity, int noise, int range, float minDD, float minDP) {
        super.configure(sensitivity, noise, range, minDD, minDP);

        String s = "S " + sensitivity + " N " + noise + " R " + range;
        Log.d(TAG, "Configure " + mDeviceName + " " + s);
    }

    public void close() {
        Log.d(TAG, "Closing " + mDeviceName);
        if (mTimer != null)
            mTimer.cancel();
        mTimer = null;
    }
}
