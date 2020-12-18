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
package com.cdot.utils;

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
public class ConcurrentFileByteFIFO {

    // Size of the metadata at the start of the log
    private static final int METABYTES = 3 * Integer.BYTES;
    // File is kept open as long as the log exists
    RandomAccessFile mRAF;
    // Metadata at the start of the log, stored in the first METABYTES
    private int mCapacity; // max buffer size (bytes). Doesn't include METABYTES
    private int mReadPos;  // byte offset of first (oldest) data (after METABYTES)
    private int mUsed;     // number of bytes used in the buffer (always <= mCapacity)

    /**
     * Create a new buffer using the given file to store it in, and given size. Note that the
     * size must be bigger than the largest byte[] that will ever be written to the buffer,
     * or you will get buffer overflow errors.
     *
     * @param file   the file to store the buffer in
     * @param nBytes initial maximum size of the buffer. Can be changed using #setCapacityBytes
     * @throws IOException if the file already exists, or there is a problem reading it
     */
    public ConcurrentFileByteFIFO(File file, int nBytes) throws IOException {
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
     * @param file     the file containing the buffer
     * @param readOnly if true, file will be opened for read only. All methods except
     * @throws IOException if the buffer file was not found, or could not be read, or was not a valid
     *                     buffer file.
     */
    public ConcurrentFileByteFIFO(File file, boolean readOnly) throws IOException {
        if (!file.exists())
            throw new FileNotFoundException("Log buffer " + file + " does not exist");
        if (file.length() < METABYTES)
            throw new IOException("Log buffer " + file + " is empty");
        mRAF = new RandomAccessFile(file, readOnly ? "r" : "rwd");
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

    // A seek beyond the end will go to the end
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
        if (len <= 0 || pos < 0 || buf == null || pos > buf.length - len)
            throw new IllegalArgumentException("Bad args");
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

    private int _read(byte[] buf, int pos, int len) throws IOException {
        if (len <= 0 || pos < 0 || buf == null)
            throw new IllegalArgumentException("Bad args");

        int nBytes = Math.min(mUsed, Math.min(len, buf.length - pos));
        int left = Math.min(nBytes, mCapacity - mReadPos);
        go_to(mReadPos);
        mRAF.read(buf, pos, left);
        mReadPos = mod(mReadPos + left);
        if (left < nBytes) {
            assert mReadPos == 0;
            pos += left;
            go_to(0);
            mRAF.read(buf, pos, nBytes - left);
            mReadPos = left;
        }
        mUsed -= nBytes;
        return nBytes;
    }

    private int _skip(int len) {
        if (len > mUsed)
            throw new BufferUnderflowException();

        int nBytes = Math.min(mUsed, len);
        int left = Math.min(nBytes, mCapacity - mReadPos);
        mReadPos = mod(mReadPos + left);
        if (left < nBytes) {
            assert mReadPos == 0;
            mReadPos = left;
        }
        mUsed -= nBytes;
        return nBytes;
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
     * buffer in an unstructured (byte by byte) way. Cannot be used in readOnly mode
     *
     * @param newCapacity new size in samples
     * @throws IOException if there's a problem reading/writing the buffer file
     */
    public synchronized void setCapacityBytes(int newCapacity) throws IOException {
        if (newCapacity <= 0)
            throw new IllegalArgumentException("Cannot set 0 capacity");
        int writePos = getWritePos();
        if (mReadPos <= writePos && writePos < newCapacity) {
            mCapacity = newCapacity;
            return;
        }
        if (mUsed > newCapacity) {
            // Shrinking, discard
            _skip(mUsed - newCapacity);
        }
        byte[] buf = new byte[mUsed];
        _read(buf, 0, mUsed);
        mReadPos = 0;
        mUsed = 0;
        mCapacity = newCapacity;
        mRAF.setLength(mCapacity); // truncate (or extend)
        add(buf, 0, buf.length); // will rewriteMeta
    }

    /**
     * Read len bytes into buf at pos. Cannot be used in readOnly mode.
     *
     * @param buf buffer to read into
     * @param pos index in buf
     * @param len length to read
     * @return number of bytes actually read
     * @throws IOException              if there's an error reading/writing the file
     * @throws BufferUnderflowException if there isn't enough data in the buffer to complete the read
     */
    public synchronized int remove(byte[] buf, int pos, int len) throws IOException {
        int r = _read(buf, pos, len);
        rewriteMeta();
        return r;
    }

    /**
     * Adds len bytes at pos from buf to the log. Cannot be used in readOnly mode.
     *
     * @param buf buffer to write from
     * @param pos index in buf
     * @param len length to write
     * @throws IOException             if there's a problem reading/writing the buffer file
     * @throws BufferOverflowException if you are trying to write more than the buffer can hold
     */
    public synchronized void add(byte[] buf, int pos, int len) throws IOException {
        _write(buf, pos, len);
        rewriteMeta();
    }

    /**
     * Adds the entire buffer to the log. Cannot be used in readOnly mode.
     *
     * @param buf buffer to write from
     * @throws IOException             if there's a problem reading/writing the buffer file
     * @throws BufferOverflowException if you are trying to write more than the buffer can hold
     */
    public synchronized void add(byte[] buf) throws IOException {
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
     * Non-destructive read. Does not alter the buffer contents. This is the only way to retrieve
     * buffer contents when it has been opened readOnly.
     * @param buf byte buffer to fill with bytes
     * @param pos position in buf to start writing
     * @param len maximum number of bytes to return
     * @return the number of bytes read
     */
    public synchronized int snapshot(byte[] buf, int pos, int len) throws IOException {
        if (pos + len > buf.length)
            throw new BufferOverflowException(); // overflowing output buffer
        int nBytes = Math.min(len, mUsed);
        // Get the read cursor at the beginning of required segment
        int snapPos = mod(mCapacity + mReadPos + mUsed - nBytes);
        int left = Math.min(nBytes, mCapacity - snapPos);
        if (left == 0)
            return 0;
        go_to(snapPos);
        mRAF.read(buf, pos, left);
        // Read forward from 0
        if (left < nBytes) {
            mRAF.seek(METABYTES);
            mRAF.read(buf, pos + left, mUsed - left);
        }
        return nBytes;
    }
}

