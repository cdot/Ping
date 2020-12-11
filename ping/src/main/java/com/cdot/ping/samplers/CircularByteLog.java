package com.cdot.ping.samplers;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;

/**
 * A FIFO buffer of a fixed maximum size implemented in a disk file.
 * The public methods of this class are synchronized on the object, but not on the underlying
 * file that implements it.
 */
public class CircularByteLog {

    // File is kept open as long as the log exists
    RandomAccessFile mRAF;

    // Size of the metadata at the start of the log
    private static final int METABYTES = 3 * Integer.BYTES;
    // Metadata at the start of the log, stored in the first METABYTES
    private int mCapacity; // max buffer size (bytes). Doesn't include METABYTES
    private int mReadPos;  // byte offset of first (oldest) data (after METABYTES)
    private int mUsed;     // number of bytes used in the buffer (always <= mCapacity)

    /**
     * Create a new buffer using the given file to store it in, and given size. Note that the
     * size must be bigger than the largest byte[] that will ever be written to the buffer,
     * or you will get buffer overflow errors.
     *
     * @param file the file to store the buffer in
     * @throws IOException if the file already exists, or there is a problem reading it
     */
    public CircularByteLog(File file, int nBytes) throws IOException {
        if (file.exists())
            throw new IOException(file + " already exists");
        mRAF = new RandomAccessFile(file, "rwd");
        mCapacity = nBytes;
        mReadPos = 0;
        mUsed = 0;
        rewriteMeta();
    }

    /**
     * Open an existing buffer.
     *
     * @param file the file containing the buffer
     * @throws IOException if the buffer file was not found, or could not be read, or was not a valid
     *                     buffer file.
     */
    public CircularByteLog(File file) throws IOException {
        if (!file.exists())
            throw new FileNotFoundException("Log buffer " + file + " does not exist");
        if (file.length() < METABYTES)
            throw new IOException("Log buffer " + file + " is empty");
        mRAF = new RandomAccessFile(file, "rwd");
        mRAF.seek(0);
        mCapacity = mRAF.readInt();
        mReadPos = mRAF.readInt();
        mUsed = mRAF.readInt();
        if (mCapacity <= 0)
            throw new IOException(file + " max size is " + mCapacity);
    }

    // Update the meta block in the file
    private void rewriteMeta() throws IOException {
        mRAF.seek(0);
        mRAF.writeInt(mCapacity);
        mRAF.writeInt(mReadPos);
        mRAF.writeInt(mUsed);
    }

    private void go_to(int offset) throws IOException {
        mRAF.seek(METABYTES + offset);
    }

    // Constrain p to be within the max size of the buffer
    private int mod(int p) {
        return p % mCapacity;
    }

    private int getWritePos() {
        return mod(mReadPos + mUsed);
    }

    private void _write(byte[] buf, int pos, int len) throws IOException {
        if (buf.length > mCapacity)
            throw new BufferOverflowException();

        // See how much space is available. We might stomp the mReadPos but that's OK, it has to move anyway.
        int writePos = getWritePos();
        int available = mCapacity - writePos;

        if (available < len) {
            // We're going to have to wrap
            go_to(writePos);
            // If we are going to overrun the read pointer, shift it
            if (mReadPos > writePos) {
                mUsed -= mReadPos - writePos;
                mReadPos = 0;
            }
            mRAF.write(buf, pos, available);
            mUsed += available;
            pos += available;
            len -= available;
            writePos = 0;
        }
        // There is space now to write the rest of buf at pos; we might have to skip some bytes
        if (mReadPos >= writePos && mReadPos < writePos + len && mUsed > 0) {
            mUsed -= writePos + len - mReadPos;
            mReadPos = mod(writePos + len);
        }
        go_to(writePos);
        mRAF.write(buf, pos, len);
        mUsed += len;
    }

    private void _read(byte[] buf, int pos, int len) throws IOException {
        if (len > mUsed)
            throw new BufferUnderflowException();

        int left = Math.min(mUsed, Math.min(mCapacity - mReadPos, len));
        go_to(mReadPos);
        mRAF.read(buf, pos, left);
        mReadPos = mod(mReadPos + left);
        mUsed -= left;
        if (left < len) {
            pos += left; len -= left;
            left = Math.min(mUsed, Math.min(mCapacity - mReadPos, len));
            go_to(mReadPos);
            mRAF.read(buf, pos, left);
            mReadPos = mod(mReadPos + left);
            mUsed -= left;
        }
    }

    private void _skip(int len) {
        if (len > mUsed)
            throw new BufferUnderflowException();

        int left = Math.min(mUsed, Math.min(mCapacity - mReadPos, len));
        mReadPos = mod(mReadPos + left);
        mUsed -= left;
        if (left < len) {
            len -= left;
            left = Math.min(mUsed, Math.min(mCapacity - mReadPos, len));
            mReadPos = mod(mReadPos + left);
            mUsed -= left;
        }
    }

    /**
     * Get the current usage of the log
     *
     * @return the used size in bytes
     */
    public int getUsedBytes() {
        return mUsed;
    }

    /**
     * Get the current max size of the log
     *
     * @return the max size in bytes; the log should never be bigger than this
     */
    public int getCapacityBytes() {
        return mCapacity;
    }

    /**
     * Resize the buffer. Note that the size must be bigger than the largest byte[] that will
     * ever be written to the buffer, or you will get buffer overflow errors. If the new size is
     * less than the currently used capacity of the buffer, then data will be lost from the
     * buffer in an unstructured (byte by byte) way.
     *
     * @param newCapacity new size in samples
     * @throws IOException if there's a problem reading/writing the buffer file
     */
    public synchronized void setCapacityBytes(int newCapacity) throws IOException {
        int writePos = getWritePos();
        if (mReadPos <= writePos && writePos < newCapacity) {
            mCapacity = newCapacity;
            return;
        }
        if (mUsed > newCapacity)
            // Shrinking, discard
            _skip(mUsed - newCapacity);
        byte[] buf = new byte[mUsed];
        _read(buf, 0, mUsed);
        mReadPos = 0;
        mCapacity = newCapacity;
        writeBytes(buf);
    }

    /**
     * Read len bytes into buf at pos
     *
     * @param buf buffer to read into
     * @param pos index in buf
     * @param len length to read
     * @throws IOException if there's an error reading/writing the file
     * @throws BufferUnderflowException if there isn't enough data in the buffer to complete the read
     */
    public synchronized void readBytes(byte[] buf, int pos, int len) throws IOException {
        _read(buf, pos, len);
        rewriteMeta();
    }

    /**
     * Read len bytes at the read pointer
     *
     * @throws IOException if there's a problem reading/writing the buffer file
     * @throws BufferUnderflowException if there isn't enough data in the buffer to complete the read
     */
    public synchronized void readBytes(byte[] buf) throws IOException {
        readBytes(buf, 0, buf.length);
    }

    /**
     * Read len bytes at the read pointer
     *
     * @throws IOException if there's a problem reading/writing the buffer file
     * @throws BufferUnderflowException if there isn't enough data in the buffer to complete the read
     */
    public synchronized byte[] readBytes(int len) throws IOException {
        byte[] buf = new byte[len];
        readBytes(buf, 0, len);
        return buf;
    }

    /**
     * Adds len bytes at pos from buf to the log
     *
     * @param buf buffer to write from
     * @param pos index in buf
     * @param len length to write
     * @throws IOException if there's a problem reading/writing the buffer file
     * @throws BufferOverflowException if you are trying to write more than the buffer can hold
     */
    public synchronized void writeBytes(byte[] buf, int pos, int len) throws IOException {
        _write(buf, pos, len);
        rewriteMeta();
    }

    /**
     * Read write buf to the buffer
     *
     * @throws IOException if there's a problem reading/writing the buffer file
     * @throws BufferOverflowException if you are trying to write more than the buffer can hold
     */
    public void writeBytes(byte[] buf) throws IOException {
        _write(buf, 0, buf.length);
        rewriteMeta();
    }

    /**
     * Close the log, freeing any resources. This will make the log unusable.
     *
     * @throws IOException if there's a problem reading/writing the buffer file
     */
    public synchronized void close() throws IOException {
        mRAF.close();
        mRAF = null;
    }

    /**
     * Get a snapshot of the buffer. Does not alter the buffer contents.
     * @return a byte[] containing a copy of the buffer contents
     */
    public synchronized byte[] snapshotBytes() throws IOException {
        byte[] buf = new byte[mUsed];

        int left = Math.min(mUsed, mCapacity - mReadPos);
        go_to(mReadPos);
        mRAF.read(buf, 0, left);
        if (left < mUsed) {
            mRAF.seek(METABYTES);
            mRAF.read(buf, left, mUsed - left);
        }
        return buf;
    }
}

