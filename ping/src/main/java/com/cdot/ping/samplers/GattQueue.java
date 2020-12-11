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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manager for synchronous operation on GATT devices. The Android BluetoothLE stack falls over on
 * a regular basis when doing asynchronous operations, so we use this queue manager to handle them
 * sequentially.
 * <p>
 * Operations are queued and executed one at a time. A timer is kept that will terminate any
 * operation that fails to finish.
 * <p>
 * Based on the GattManager class from https://github.com/NordicPlayground/puck-central-android
 * </p>
 */
class GattQueue {

    static final Map<Integer, String> BLE_STATUS = new HashMap<Integer, String>() {
        {
            put(0x00, "STATUS_CODE_SUCCESS");
            put(0x01, "STATUS_CODE_UNKNOWN_BTLE_COMMAND");
            put(0x02, "STATUS_CODE_UNKNOWN_CONNECTION_IDENTIFIER");
            put(0x05, "AUTHENTICATION_FAILURE");
            put(0x06, "STATUS_CODE_PIN_OR_KEY_MISSING");
            put(0x07, "MEMORY_CAPACITY_EXCEEDED");
            put(0x08, "CONNECTION_TIMEOUT");
            put(0x0C, "STATUS_CODE_COMMAND_DISALLOWED");
            put(0x12, "STATUS_CODE_INVALID_BTLE_COMMAND_PARAMETERS");
            put(0x13, "REMOTE_USER_TERMINATED_CONNECTION");
            put(0x14, "REMOTE_DEV_TERMINATION_DUE_TO_LOW_RESOURCES");
            put(0x15, "REMOTE_DEV_TERMINATION_DUE_TO_POWER_OFF");
            put(0x16, "LOCAL_HOST_TERMINATED_CONNECTION");
            put(0x1A, "UNSUPPORTED_REMOTE_FEATURE");
            put(0x1E, "STATUS_CODE_INVALID_LMP_PARAMETERS");
            put(0x1F, "STATUS_CODE_UNSPECIFIED_ERROR");
            put(0x22, "STATUS_CODE_LMP_RESPONSE_TIMEOUT");
            put(0x24, "STATUS_CODE_LMP_PDU_NOT_ALLOWED");
            put(0x28, "INSTANT_PASSED");
            put(0x29, "PAIRING_WITH_UNIT_KEY_UNSUPPORTED");
            put(0x2A, "DIFFERENT_TRANSACTION_COLLISION");
            put(0x3A, "CONTROLLER_BUSY");
            put(0x3B, "CONN_INTERVAL_UNACCEPTABLE");
            put(0x3C, "DIRECTED_ADVERTISER_TIMEOUT");
            put(0x3D, "CONN_TERMINATED_DUE_TO_MIC_FAILURE");
            put(0x3E, "CONN_FAILED_TO_BE_ESTABLISHED");
            put(0x81, "GATT internal error");
            put(0x85, "Dreaded 133 bug");
        }
    };
    private static final String TAG = GattQueue.class.getSimpleName();
    // DEBUG describe the device
    // Also acts as a test of the queue
    private static final String[] PERMISSIONS_BITS = new String[]{
            "PERMISSION_READ", // 0x01
            "PERMISSION_READ_ENCRYPTED", // 0x02
            "PERMISSION_READ_ENCRYPTED_MITM", // 0x04
            "UNKNOWN", // 0x08
            "PERMISSION_WRITE", // 0x10
            "PERMISSION_WRITE_ENCRYPTED", // 0x20;
            "PERMISSION_WRITE_ENCRYPTED_MITM", // 0x40;
            "PERMISSION_WRITE_SIGNED", // 0x80
            "PERMISSION_WRITE_SIGNED_MITM" // 0x100;
    };
    private static final String[] PROPERTIES_BITS = new String[]{
            "PROPERTY_BROADCAST", // 0x1
            "PROPERTY_READ", // 0x2
            "PROPERTY_WRITE_NO_RESPONSE", // 0x4
            "PROPERTY_WRITE", // 0x8
            "PROPERTY_NOTIFY", // 0x10
            "PROPERTY_INDICATE", // 0x20
            "PROPERTY_SIGNED_WRITE", // 0x40
            "PROPERTY_EXTENDED_PROPS" // 0x80
    };
    private final ConcurrentLinkedQueue<Operation> mQueue = new ConcurrentLinkedQueue<>();
    private Operation mCurrentOperation = null;
    private Timer mCurrentOperationTimeout = null;
    private final BluetoothGatt mBluetoothGatt;
    /**
     * Create a new queue for sequencing GATT operations
     *
     * @param gatt the device being managed
     * @param cb   callback handler
     */
    public GattQueue(BluetoothGatt gatt, Callback cb) {
        mBluetoothGatt = gatt;
        cb.setQueue(this);
    }

    private static String describeBits(final String[] names, int val) {
        StringBuilder descr = new StringBuilder();
        for (int idx = 0; idx < names.length; idx++) {
            if ((val & (1 << idx)) != 0) {
                if (descr.length() > 0)
                    descr.append("|");
                descr.append(names[idx]);
            }
        }
        return descr.toString();
    }

    // Generate a full description of the device on Log.d
    static void describeFromCache(BluetoothGatt gatt) {
        StringBuilder descr = new StringBuilder();
        for (BluetoothGattService s : gatt.getServices()) {
            descr.append("<service uid=").append(s.getUuid()).append(" type=")
                    .append(s.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY ? "primary" : "secondary")
                    .append(">");
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                descr.append("<characteristic uid=").append(c.getUuid())
                        .append(" props=").append(describeBits(PROPERTIES_BITS, c.getProperties()))
                        .append(" perms=").append(describeBits(PERMISSIONS_BITS, c.getPermissions()))
                        .append(" string='").append(c.getStringValue(0)).append("'")
                        .append(" value=").append(Arrays.toString(c.getValue())).append(">");
                for (BluetoothGattDescriptor d : c.getDescriptors()) {
                    descr.append("<descriptor uid=").append(d.getUuid())
                            .append(" perms=").append(describeBits(PERMISSIONS_BITS, c.getPermissions()))
                            .append(" value=").append(Arrays.toString(d.getValue())).append(" />");
                }
                descr.append("</characteristic>");
            }
            descr.append("</service>");
        }
        Log.d(TAG, descr.toString());
    }

    void describeWithValues(BluetoothGatt gatt) {
        GattQueue.Operation postOp = new GattQueue.Operation() {
            public void execute(BluetoothGatt gatt) {
                describeFromCache(gatt);
            }
        };

        for (BluetoothGattService s : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0)
                    queue(new GattQueue.CharacteristicRead(c, null));
                for (BluetoothGattDescriptor d : c.getDescriptors())
                    queue(new GattQueue.DescriptorRead(d, null));
            }
        }
        queue(postOp);
    }

    /**
     * Called when an operation is complete, either because of a callback or because it timed out
     */
    private synchronized void operationComplete() {
        if (mCurrentOperation == null) {
            Log.e(TAG, "operationComplete, but no current operation");
            return;
        }
        mCurrentOperation = null;
        if (mCurrentOperationTimeout != null)
            mCurrentOperationTimeout.cancel();
        mCurrentOperationTimeout = null;
    }

    /**
     * Pick the next operation off the queue
     */
    private synchronized void startNextOperation() {
        if (mQueue.size() == 0)
            return;

        // Get the next operation
        mCurrentOperation = mQueue.poll();
        Log.v(TAG, "Starting operation " + mCurrentOperation);

        // Initiate timeout timer
        mCurrentOperationTimeout = new Timer();
        mCurrentOperationTimeout.schedule(new TimerTask() {
            public void run() {
                Log.d(TAG, "Operation timed out " + mCurrentOperation);
                mCurrentOperationTimeout = null;
                operationComplete();
                startNextOperation();
            }
        }, mCurrentOperation.getTimeoutInMillis());

        mCurrentOperation.execute(mBluetoothGatt);
    }

    /**
     * Add an operation to the queue, and start it if nothing is currently running
     *
     * @param operation subclass of GattManager#Operation
     */
    public synchronized void queue(Operation operation) {
        mQueue.add(operation);
        Log.v(TAG, "Queueing operation " + operation);
        if (mCurrentOperation == null)
            startNextOperation();
    }

    // Callback for a read operation on a characteristic or descriptor
    public interface ReadCallback {
        void call(byte[] value);
    }
    // END OF DEBUG

    /**
     * Base class of Gatt read/write operations
     */
    public static abstract class Operation {
        private static final int DEFAULT_TIMEOUT_IN_MILLIS = 10000;

        public abstract void execute(BluetoothGatt bluetoothGatt);

        public int getTimeoutInMillis() {
            return DEFAULT_TIMEOUT_IN_MILLIS;
        }
    }

    /**
     * Wrapper for BluetoothGatt#readCharacteristic
     */
    public static class CharacteristicRead extends Operation {

        private final ReadCallback mCallback;
        private final BluetoothGattCharacteristic mCh;

        public CharacteristicRead(BluetoothGattCharacteristic characteristic, ReadCallback callback) {
            mCh = characteristic;
            mCallback = callback;
        }

        @Override
        public void execute(BluetoothGatt gatt) {
            Log.d(TAG, "reading from characteristic " + mCh.getUuid());
            gatt.readCharacteristic(mCh);
        }

        public void onRead(BluetoothGattCharacteristic characteristic) {
            if (mCallback != null)
                mCallback.call(characteristic.getValue());
        }

        @NonNull
        public String toString() {
            return "Read characteristic " + mCh.getUuid();
        }
    }

    /**
     * Wrapper for BluetoothGatt#writeCharacteristic
     */
    public static class CharacteristicWrite extends Operation {

        private final BluetoothGattCharacteristic mCh;

        public CharacteristicWrite(BluetoothGattCharacteristic characteristic) {
            mCh = characteristic;
        }

        @Override
        public void execute(BluetoothGatt gatt) {
            Log.d(TAG, "writing to characteristic " + mCh.getUuid());
            gatt.writeCharacteristic(mCh);
        }

        @NonNull
        public String toString() {
            return "Write characteristic " + mCh.getUuid();
        }
    }

    /**
     * Wrapper for BluetoothGatt#readDescriptor
     */
    public static class DescriptorRead extends Operation {

        private final ReadCallback mCallback;
        private final BluetoothGattDescriptor mDe;

        public DescriptorRead(BluetoothGattDescriptor descriptor, ReadCallback callback) {
            mDe = descriptor;
            mCallback = callback;
        }

        @Override
        public void execute(BluetoothGatt gatt) {
            Log.d(TAG, "reading from descriptor " + mDe.getUuid());
            gatt.readDescriptor(mDe);
        }

        public void onRead(BluetoothGattDescriptor descriptor) {
            if (mCallback != null)
                mCallback.call(descriptor.getValue());
        }

        @NonNull
        public String toString() {
            return "Read descriptor " + mDe.getUuid();
        }
    }

    /**
     * Wrapper for BluetoothGatt#writeDescriptor
     */
    public static class DescriptorWrite extends Operation {
        private final BluetoothGattDescriptor mDe;

        public DescriptorWrite(BluetoothGattDescriptor descriptor) {
            mDe = descriptor;
        }

        @Override
        public void execute(BluetoothGatt gatt) {
            Log.d(TAG, "writing to descriptor " + mDe.getUuid());
            gatt.writeDescriptor(mDe);
        }

        @NonNull
        public String toString() {
            return "Write descriptor " + mDe.getUuid();
        }
    }

    /**
     * Subclass of BluetoothGattCallback that is designed to sit behind the caller's
     * BluetoothCallback.
     */
    static class Callback extends BluetoothGattCallback {
        GattQueue mQ = null;

        public void setQueue(GattQueue mq) {
            mQ = mq;
        }

        // Subclasses must call super.onConnectionStateChange()
        @Override // BluetoothGattCallback
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onConnectionStateChange: Bad status " + status + " = " + BLE_STATUS.get(status));
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to server. Asking it for its services");
                if (!gatt.discoverServices())
                    Log.e(TAG, "discoverServices failed");

                // Only when service discovery is complete can we broadcast that the connection
                // is available. See onServicesDiscovered, below.
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED)
                Log.d(TAG, "Disconnected from server");
            else if (newState == BluetoothProfile.STATE_CONNECTING)
                Log.d(TAG, "Connecting to server");
            else if (newState == BluetoothProfile.STATE_DISCONNECTING)
                Log.d(TAG, "Disconnecting from server");
        }

        // Subclasses must call super.onServicesDiscovered()
        @Override // BluetoothGattCallback
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered");
            if (status != BluetoothGatt.GATT_SUCCESS)
                // Watch for status 133 bug
                Log.e(TAG, "onServicesDiscovered FAILED: " + status);
            //mQ.describe(); // debug
        }

        // Subclasses should NOT implement this
        @Override // BluetoothGattCallback
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "Descriptor " + descriptor.getUuid() + " read");
            ((DescriptorRead) mQ.mCurrentOperation).onRead(descriptor);
            mQ.operationComplete();
            mQ.startNextOperation();
        }

        @Override // BluetoothGattCallback
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "Descriptor " + descriptor.getUuid() + " written");
            mQ.operationComplete();
            mQ.startNextOperation();
        }

        @Override // BluetoothGattCallback
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "Characteristic " + characteristic.getUuid() + " read");
            ((CharacteristicRead) mQ.mCurrentOperation).onRead(characteristic);
            mQ.operationComplete();
            mQ.startNextOperation();
        }

        @Override // BluetoothGattCallback
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "Characteristic " + characteristic.getUuid() + " written");
            mQ.operationComplete();
            mQ.startNextOperation();
        }
    }
}
