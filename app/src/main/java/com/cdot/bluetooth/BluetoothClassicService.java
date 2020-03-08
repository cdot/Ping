package com.cdot.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Generic service for talking to Bluetooth Classic devices. The application talks to the service
 * through a SonarChat interface.
 * THIS SERVICE IS UNTESTED AND PROBABLY DOESN'T WORK
 */
public class BluetoothClassicService extends BluetoothService {

    private static final String TAG = "BluetoothClassicService";
    private static final String MY_NAME = TAG;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter mAdapter;

    private abstract class Step extends Thread {
        // Close sockets when shutting down
        abstract void close();
        // Write bytes to device
        void write(byte[] buffer) {}
    }

    private Step mThread;

    public BluetoothClassicService() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It is initiated when the service is started, and
     * runs until a connection is accepted (or until cancelled).
     */
    private class AcceptThread extends Step {
        // Act as server by holding open BluetoothServerSocket
        private BluetoothServerSocket mmServerSocket = null;

        AcceptThread(BluetoothServerSocket serverSocket) {
            mmServerSocket = serverSocket;
        }

        public void run() {
            Log.d(BluetoothClassicService.TAG, "BEGIN mAcceptThread" + this);
            setName("AcceptThread");
            try {
                BluetoothSocket socket = mmServerSocket.accept(); // Blocking!
                mmServerSocket.close();
                    synchronized (BluetoothClassicService.this) {
                        Log.d(TAG, "accepted");
                        mThread = new ConnectedThread(socket);
                        mThread.start();
                        Intent intent = new Intent(ACTION_BT_CONNECTED);
                        intent.putExtra(EXTRA_DATA, socket.getRemoteDevice().getName());
                        sendBroadcast(intent);
                    }
            } catch (IOException e2) {
                Log.e(BluetoothClassicService.TAG, "accept() failed", e2);
            }
            Log.i(BluetoothClassicService.TAG, "END mAcceptThread");
        }

        void close() {
            Log.d(BluetoothClassicService.TAG, "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(BluetoothClassicService.TAG, "close() failed", e);
            }
        }
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Step {
        private BluetoothDevice mmDevice;
        private BluetoothSocket mmSocket;

        ConnectThread(BluetoothSocket socket, BluetoothDevice device) {
            mmDevice = device;
            mmSocket = socket;
        }

        public void run() {
            Log.i(BluetoothClassicService.TAG, "BEGIN mConnectThread");
            setName("ConnectThread");
            mAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
                synchronized (BluetoothClassicService.this) {
                    Log.d(TAG, "connected");
                    mThread = new ConnectedThread(mmSocket);
                    mThread.start();
                    Intent intent = new Intent(ACTION_BT_CONNECTED);
                    intent.putExtra(EXTRA_DATA, mmDevice.getName());
                    sendBroadcast(intent);
                }
            } catch (IOException e) {
                Intent intent = new Intent(ACTION_BT_DISCONNECTED);
                intent.putExtra(EXTRA_DATA, CANNOT_CONNECT);
                sendBroadcast(intent);
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(BluetoothClassicService.TAG, "unable to close() socket during connection failure", e2);
                }
                start(); // try again
            }
        }

        void close() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(BluetoothClassicService.TAG, "close() failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Step {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final BluetoothSocket mmSocket;

        ConnectedThread(BluetoothSocket socket) {
            Log.d(BluetoothClassicService.TAG, "create ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(BluetoothClassicService.TAG, "sockets not created", e);
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(BluetoothClassicService.TAG, "BEGIN mConnectedThread");
            // Maximum read volume 1024 bytes
            byte[] buffer = new byte[1024];
            while (!isInterrupted()) {
                try {
                    if (mmInStream.read(buffer) > 0) {
                        Intent intent = new Intent(ACTION_BT_DATA_AVAILABLE);
                        intent.putExtra(EXTRA_DATA, buffer);
                    }
                } catch (IOException e) {
                    Log.e(BluetoothClassicService.TAG, "disconnected", e);
                    Intent intent = new Intent(ACTION_BT_DISCONNECTED);
                    intent.putExtra(EXTRA_DATA, CONNECTION_LOST);
                    sendBroadcast(intent);
                }
            }
        }

        void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
                Log.e(BluetoothClassicService.TAG, "Exception during write", e);
            }
        }

        void close() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(BluetoothClassicService.TAG, "close() of connect socket failed", e);
            }
        }
    }

    @Override
    public synchronized void onCreate() {
        Log.d(TAG, "onCreate");
        if (mThread != null) {
            mThread.interrupt();
            mThread.close();
        }
        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
        if (bta == null)
            throw new Error("No bluetooth support");
        try {
            BluetoothServerSocket serverSocket = bta.listenUsingRfcommWithServiceRecord(MY_NAME, MY_UUID);
            mThread = new AcceptThread(serverSocket);
            mThread.start();
        } catch (IOException e) {
            Log.e(BluetoothClassicService.TAG, "Unable to obtain BluetoothServerSocket", e);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mThread.close();
    }

    @Override
    public synchronized void connect(BluetoothDevice device) {
        Log.d(TAG, "connect to: " + device.getName());
        if (mThread instanceof AcceptThread) {
            Log.d(TAG, "cannot connect, AcceptThread still active");
            return;
        }
        if (mThread != null)
            mThread.interrupt();
        BluetoothSocket socket = null;
        try {
            socket = device.createRfcommSocketToServiceRecord(BluetoothClassicService.MY_UUID);
        } catch (IOException e) {
            Log.e(BluetoothClassicService.TAG, "create() failed", e);
        }
        mThread = new ConnectThread(socket, device);
        mThread.start();
    }

    @Override
    public synchronized void disconnect() {
        if (mThread != null && !(mThread instanceof AcceptThread))
            mThread.interrupt();
        if (mThread instanceof ConnectedThread)
            mThread.close();
    }

    public void close() {

    }

    @Override
    public void write(byte[] out) {
        synchronized (this) {
            if (mThread instanceof ConnectedThread)
                mThread.write(out);
        }
    }
}

