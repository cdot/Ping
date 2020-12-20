/*
 * Copyright © 2020 C-Dot Consultants
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.cdot.ping.samplers;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import no.nordicsemi.android.ble.callback.FailCallback;
import no.nordicsemi.android.ble.callback.SuccessCallback;
import no.nordicsemi.android.ble.callback.profile.ProfileDataCallback;
import no.nordicsemi.android.ble.data.Data;

/**
 * Talk to FishFinder device using classic Bluetooth stream interface. This works fine, and
 * it's debatable if using BLE confers any real advantage.
 */
public class SonarClassic implements SonarBluetooth.BTImplementation {
    public static final String TAG = SonarClassic.class.getSimpleName();

    LoggingService mService;
    SonarBluetooth mSC;
    BluetoothSocket mBTSocket;
    ConnectedThread mConnectedThread;
    BluetoothDevice mDevice;
    int mConnectionState = SonarBluetooth.BT_STATE_DISCONNECTED;
    // SPP = serial port profile
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public SonarClassic(@NonNull final LoggingService service) {
        mService = service;
    }

    public void setCommon(SonarBluetooth observer) {
        mSC = observer;
    }

    @Override
    public BluetoothDevice getBluetoothDevice() {
        if (mBTSocket != null)
            return mBTSocket.getRemoteDevice();
        return mDevice;
    }

    @Override
    public int getConnectionState() {
        return mConnectionState;
    }

    @Override
    public void close() {
    }

    @Override
    public void sendConfiguration(byte[] data) {
        // TODO: make the write pend?
        if (mConnectedThread != null)
            mConnectedThread.write(data);
    }

    public SonarBluetooth.Request connectToDevice(BluetoothDevice device) {
        mSC.onDeviceConnecting(device);
        return new ConnectRequest(device);
    }

    @Override
    public SonarBluetooth.Request disconnectFromDevice() {
        mSC.onDeviceDisconnecting(mDevice);
        return new DisconnectRequest(mDevice);
    }

    private class ConnectedThread extends Thread {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        ProfileDataCallback mSonarHandler;

        public ConnectedThread(BluetoothSocket socket, ConnectRequest request) {
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                if (request.mFailCallback != null)
                    request.mFailCallback.onRequestFailed(socket.getRemoteDevice(), SonarBluetooth.BT_STATE_DISCONNECTED);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            mSonarHandler = mSC.getSonarHandler();
        }

        public void run() {
            int BLOCKSIZE = 18;
            byte[] buffer = new byte[BLOCKSIZE];  // buffer store for the stream
            int bytes; // bytes returned from read()
            mSC.onDeviceReady(mDevice);
            // Keep listening to the InputStream until an exception occurs
            while (!isInterrupted()) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.available();
                    if (bytes < BLOCKSIZE)
                        SystemClock.sleep(100); // pause and wait for a packet - 100ms = 10kHz
                    else {
                        if (mmInStream.read(buffer, 0, BLOCKSIZE) == BLOCKSIZE) {
                            // TODO: how many bytes per sample
                            mSonarHandler.onDataReceived(mDevice, new Data(buffer));
                        } else {
                            Log.e(TAG, "read() came up short");
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Read failed " + e);
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) {
                Log.e(TAG, "Write failed " + e);
            }
        }
    }

    private abstract class MyRequest implements SonarBluetooth.Request {
        SuccessCallback mSuccessCallback;
        FailCallback mFailCallback;
        BluetoothDevice mDevice;

        MyRequest(BluetoothDevice device) {
            mDevice = device;
        }

        @Override
        public SonarBluetooth.Request done(@NonNull SuccessCallback callback) {
            mSuccessCallback = callback;
            return this;
        }

        @Override
        public SonarBluetooth.Request fail(@NonNull FailCallback callback) {
            mFailCallback = callback;
            return this;
        }
    }

    private class ConnectRequest extends MyRequest {
        ConnectRequest(BluetoothDevice device) {
           super(device);
        }

        public void enqueue() {
            mConnectionState = SonarBluetooth.BT_STATE_CONNECTING;
            try {
                mBTSocket = mDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                Log.d(TAG, "Socket created");
           } catch (IOException e) {
                Log.e(TAG, "Socket create failed " + e);
                if (mFailCallback != null)
                    mFailCallback.onRequestFailed(mDevice, SonarBluetooth.BT_STATE_DISCONNECTED);
            }
            // Establish the Bluetooth socket connection.
            try {
                mBTSocket.connect();
                mConnectedThread = new ConnectedThread(mBTSocket, this);
                mConnectedThread.start();
                mConnectionState = SonarBluetooth.BT_STATE_CONNECTED;
                if (mSuccessCallback != null)
                    mSuccessCallback.onRequestCompleted(mDevice);
            } catch (IOException e) {
                Log.e(TAG, "Connect failed " + e);
                try {
                    mBTSocket.close();
                    mSC.onDeviceDisconnecting(mDevice);
                } catch (IOException ignore) {
                }
                if (mFailCallback != null)
                    mFailCallback.onRequestFailed(mDevice, SonarBluetooth.BT_STATE_DISCONNECTED);
            }
        }
    }

    private class DisconnectRequest extends MyRequest {
        DisconnectRequest(BluetoothDevice device) {
            super(device);
        }

        public void enqueue() {
            mConnectionState = SonarBluetooth.BT_STATE_DISCONNECTING;
            if (mConnectedThread != null) {
                mConnectedThread.interrupt();
                mConnectedThread = null;
            }
            try {
                if (mBTSocket != null)
                    mBTSocket.close();
                if (mDevice == null)
                    if (mFailCallback != null)
                        mFailCallback.onRequestFailed(mDevice, SonarBluetooth.BT_STATE_DISCONNECTED);
                else if (mSuccessCallback != null)
                    mSuccessCallback.onRequestCompleted(mDevice);
            } catch (IOException e) {
                Log.e(TAG, "Socket close failed " + e);
                if (mFailCallback != null)
                    mFailCallback.onRequestFailed(mDevice, SonarBluetooth.BT_STATE_DISCONNECTED);
            }
        }
    }
}

