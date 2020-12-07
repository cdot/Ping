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
import android.content.res.Resources;

import androidx.annotation.CallSuper;

/**
 * Base class of samplers. Samplers interface to sample sources and call methods in LoggingService.
 */
abstract class Sampler {

    // The service we are sampling for
    protected LoggingService mService;

    // Will be set true on startup and when logging is turned on
    protected boolean mMustLogNextSample = true;

    abstract String getTag();

    /**
     * The sampler has been attached to a logging service. The sampler must have onDestroy
     * called to detach from the service again and allow garbage collection.
     */
    @CallSuper
    void onAttach(LoggingService service) {
       mService = service;
    }

    /**
     * Something is binding to the logging service we're attached to. Use this to alert
     * the binder to current status.
     */
    void onBind() {}

    /**
     * Called when the app is stopped and recording is disabled (so not foregrounding), or the
     * notification has had the "stop" button pressed. The sampler shouldn't deliver any more
     * samples to the LoggingService after this call.
     */
    abstract void stopSampling();

    /**
     * Get the text string this sampler contributes to the notification. This string should
     * incorporate the most recent sample, and the connected state of the sampler.
     * @return a text string illustrating the current state of the sampler.
     */
    public abstract String getNotificationStateText(Resources r);

    /**
     * The logging service is being destroyed. Note that stopSampling will already have been called.
     */
    @CallSuper
    void onDestroy() {
        mService = null;
    }

    /**
     * Connect one or more samplers to a bluetooth device
     * @param btd
     */
    void connectToDevice(BluetoothDevice btd) {
    }

    /**
     * Get the first bluetooth device that has been connected to by a sampler.
     * @return the device
     */
    BluetoothDevice getConnectedDevice() {
       return null;
    }
}
