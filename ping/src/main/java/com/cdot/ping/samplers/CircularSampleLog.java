package com.cdot.ping.samplers;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;

/**
 * A circular sample log using a CircularByteLog to store in a disk file.
 */
public class CircularSampleLog extends CircularByteLog {

    /**
     * Construct a new sample log. The log file may not pre-exist.
     *
     * @param file    file to store the log in
     * @param maxSize maximum size of the log, in samples
     * @throws IOException if the file already exists, or there's problem writing it
     */
    public CircularSampleLog(File file, int maxSize) throws IOException {
        super(file, maxSize * Sample.BYTES);
    }

    /**
     * Open an existing sample log
     *
     * @param file file containing the log
     * @throws IOException if the log doesn't exist, or there's a problem reading it
     */
    public CircularSampleLog(File file) throws IOException {
        super(file);
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
     * Get the used size of the log
     *
     * @return the used in samples
     */
    public int getUsedSamples() {
        return getUsedBytes() / Sample.BYTES;
    }

    /**
     * Set the maximum capacity of the log. If this capacity is would be exceeded, old samples are
     * discarded to make space.
     *
     * @param nsamples new capacity
     * @throws IOException if there's a problem with the log file
     */
    public void setCapacitySamples(int nsamples) throws IOException {
        setCapacityBytes(nsamples * Sample.BYTES);
    }

    /**
     * Adds a sample to the log
     *
     * @throws IOException if there's a problem with the log file
     */
    public void writeSample(Sample sample) throws IOException {
        writeBytes(sample.getBytes());
    }

    /**
     * Read (and remove) the oldest samples from the buffer
     *
     * @return the samples removed
     * @throws IOException if there's a problem with the log file
     */
    public Sample[] readSamples(int len) throws IOException {
        byte[] buff = readBytes(len * Sample.BYTES);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buff));
        Sample[] samples = new Sample[len];
        for (int i = 0; i < len; i++)
            samples[i] = new Sample(dis);
        return samples;
    }

    /**
     * Read (and remove) the oldest sample from the buffer
     *
     * @return the sample removed
     * @throws IOException if there's a problem with the log file
     */
    public Sample readSample() throws IOException {
        byte[] buff = readBytes(Sample.BYTES);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buff));
        return new Sample(dis);
    }

    /**
     * Adds a bunch of samples to the log
     *
     * @throws IOException if there's a problem with the log file
     */
    public void writeSamples(Sample[] samples) throws IOException {
        for (Sample s : samples)
            writeSample(s);
    }

    /**
     * Snapshot the current state of the log.
     * @return an array of all the samples currently in the log
     * @throws IOException if there's a problem accessing the underlying file
     */
    public Sample[] snapshotSamples() throws IOException {
        byte[] bytes = super.snapshotBytes();
        int nSamples = bytes.length / Sample.BYTES;
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        Sample[] snap = new Sample[nSamples];
        for (int i = 0; i < nSamples; i++)
            snap[i] = new Sample(dis);
        return snap;
    }
}
