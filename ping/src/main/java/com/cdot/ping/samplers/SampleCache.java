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

import com.cdot.utils.ConcurrentFileByteFIFO;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.BufferUnderflowException;

/**
 * A circular sample log using a CircularByteLog to store in a disk file.
 */
public class SampleCache extends ConcurrentFileByteFIFO {

    /**
     * Construct a new sample log. The log file may not pre-exist.
     *
     * @param file    file to store the log in
     * @param maxSize initial maximum size of the log, in samples. Can be changed using #setCapacitySamples
     * @throws IOException if the file already exists, or there's problem writing it
     */
    public SampleCache(File file, int maxSize) throws IOException {
        super(file, maxSize * Sample.BYTES);
    }

    /**
     * Open an existing sample log
     *
     * @param file     file containing the log
     * @param readOnly if true, file will be opened for read, otherwise for read-write
     * @throws IOException if the log doesn't exist, or there's a problem reading it
     */
    public SampleCache(File file, boolean readOnly) throws IOException {
        super(file, readOnly);
    }

    /**
     * Get the current max size of the log
     *
     * @return the max size in samples; the log should never be bigger than this
     */
    public int getCapacitySamples() {
        return getCapacityBytes() / Sample.BYTES;
    }

    /**
     * Set the maximum capacity of the log. If this capacity is would be exceeded, old samples are
     * discarded to make space. Cannot be used in readOnly mode.
     *
     * @param nsamples new capacity
     * @throws IOException if there's a problem with the log file
     */
    public void setCapacitySamples(int nsamples) throws IOException {
        setCapacityBytes(nsamples * Sample.BYTES);
    }

    /**
     * Get the used size of the log
     *
     * @return the used in samples
     */
    public int getUsedSamples() {
        return getUsedBytes() / Sample.BYTES;
    }

    /**
     * Adds a sample to the log. Cannot be used in readOnly mode.
     *
     * @throws IOException if there's a problem with the log file
     */
    public void add(Sample sample) throws IOException {
        add(sample.toByteArray());
    }

    /**
     * Adds a bunch of samples to the log. Cannot be used in readOnly mode
     *
     * @throws IOException if there's a problem with the log file
     */
    public void add(Sample[] samples) throws IOException {
        for (Sample s : samples)
            add(s);
    }

    /**
     * Read (and remove) the oldest samples from the buffer. Cannot be used in readOnly mode.
     *
     * @param len the number of samples to read
     * @return the samples removed, oldest first
     * @throws IOException if there's a problem with the log file, or a buffer underflow
     */
    public Sample[] removeSamples(int len) throws IOException {
        byte[] buff = new byte[len * Sample.BYTES];
        remove(buff, 0, buff.length);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buff));
        Sample[] samples = new Sample[len];
        for (int i = 0; i < len; i++)
            samples[i] = Sample.fromDataStream(dis);
        return samples;
    }

    /**
     * Read (and remove) the oldest sample from the buffer. Cannot be used in readOnly mode.
     *
     * @return the sample removed
     * @throws IOException if there's a problem with the log file
     */
    public Sample removeSample() throws IOException {
        byte[] buff = new byte[Sample.BYTES];
        if (remove(buff, 0, Sample.BYTES) < Sample.BYTES)
            throw new BufferUnderflowException();
        return Sample.fromByteArray(buff, 0);
    }

    /**
     * Snapshot the current state of the log. This is the only way to retrieve buffer contents in
     * readonly mode.
     *
     * @param buf buffer to fill with samples
     * @param pos position in buf to start writing
     * @param len maximum number of samples to return
     * @return the number of samples read
     * @throws IOException if there's a problem accessing the underlying file
     */
    public int snapshot(Sample[] buf, int pos, int len) throws IOException {
        byte[] bytes = new byte[len * Sample.BYTES];
        int r = super.snapshot(bytes, 0, bytes.length);
        int nSamples = r / Sample.BYTES;
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < nSamples; i++)
            buf[i] = Sample.fromDataStream(dis);
        return r;
    }
}
