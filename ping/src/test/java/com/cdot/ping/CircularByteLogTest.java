package com.cdot.ping;

import com.cdot.ping.samplers.CircularByteLog;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CircularByteLogTest {

    private static final String logfile = "bytes.log";
    
    @Before
    public void killLogFile() {
        new File(logfile).delete();
    }

    private String pack(byte[] blah) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blah.length; i++)
            sb.append((char) blah[i]);
        return sb.toString();
    }

    private byte[] unpack(String blah) {
        byte[] b = new byte[blah.length()];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) blah.charAt(i);
        return b;
    }

    @Test
    public void simple() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 100);
        assertEquals(100, cl.getCapacityBytes());
        cl.writeBytes(unpack("0123456789!"));
        assertEquals(11, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
        assertEquals("0123456789!", pack(cl.readBytes(11)));
        assertEquals(0, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
    }

    @Test
    public void reopen() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 100);
        assertEquals(100, cl.getCapacityBytes());
        byte[] blah = unpack("ABCDEFGHIJK");
        cl.writeBytes(blah);
        cl.close();
        cl = new CircularByteLog(new File(logfile));
        byte[] s = cl.readBytes(blah.length);
        for (int i = 0; i < blah.length; i++)
            assertEquals(blah[i], s[i], 0);
        assertEquals(0, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
    }

    @Test
    public void wrap() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 20);
        cl.writeBytes(unpack("ABCDEFGHIJK"));
        cl.writeBytes(unpack("LMNOPQRSTUV"));
        assertEquals(20, cl.getUsedBytes());
        byte[] b = cl.readBytes(20);
        assertEquals("CDEFGHIJKLMNOPQRSTUV", pack(b));
    }

    @Test
    public void overflow() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 10);
        try {
            cl.writeBytes(unpack("ABCDEFGHIJKLMNOPQRSTUV"));
            assertTrue(false);
        } catch (BufferOverflowException boe) {
        }
    }

    @Test
    public void underflow() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 26);
        cl.writeBytes(unpack("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        try {
            cl.readBytes(27);
            assertTrue(false);
        } catch (BufferUnderflowException boe) {
        }
    }

    @Test
    public void growReadBeforeWrite() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 11);
        cl.writeBytes(unpack("ABCDEFGHIJK"));
        byte[] b = cl.readBytes(4);
        assertEquals("ABCD", pack(b));
        // readPos should be 4
        assertEquals(7, cl.getUsedBytes());

        cl.setCapacityBytes(13);
        assertEquals(7, cl.getUsedBytes());
        assertEquals(13, cl.getCapacityBytes());

        b = cl.readBytes(cl.getUsedBytes());
        assertEquals("EFGHIJK", pack(b));
    }

    @Test
    public void growWriteBeforeRead() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 11);
        cl.writeBytes(unpack("ABCDEFGHIJK"));
        byte[] b = cl.readBytes(6);
        assertEquals("ABCDEF", pack(b));
        // readPos should be 6, writePos 0

        cl.writeBytes(unpack("12")); // buffer should be: 12????GHIJK
        assertEquals(7, cl.getUsedBytes());

        cl.setCapacityBytes(13);
        assertEquals(7, cl.getUsedBytes());
        assertEquals(13, cl.getCapacityBytes());

        b = cl.readBytes(cl.getUsedBytes());
        assertEquals(7, b.length);
        assertEquals("GHIJK12", pack(b));
    }

    @Test
    public void growTrivial() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 11);
        cl.writeBytes(unpack("LMNOPQRSTUV"));
        cl.writeBytes(unpack("ABCDEFGHIJK"));
        assertEquals(11, cl.getUsedBytes());
        cl.setCapacityBytes(22);
        assertEquals(11, cl.getUsedBytes());
        assertEquals(22, cl.getCapacityBytes());
        cl.writeBytes(unpack("LMNOPQRSTUV"));
        assertEquals(22, cl.getUsedBytes());
        byte[] b = cl.readBytes(cl.getUsedBytes());
        assertEquals("ABCDEFGHIJKLMNOPQRSTUV", pack(b));
    }

    @Test
    public void shrinkReadBeforeWrite() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 11);
        cl.writeBytes(unpack("ABCDEFGHIJK"));
        byte[] b = cl.readBytes(3);
        assertEquals("ABC", pack(b));
        // readPos should be 3
        assertEquals(8, cl.getUsedBytes());

        cl.setCapacityBytes(5);

        // should have lost DEF
        assertEquals(5, cl.getUsedBytes());
        b = cl.readBytes(5);
        assertEquals("GHIJK", pack(b));
    }

    @Test
    public void shrinkWriteBeforeRead() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File(logfile), 11);
        cl.writeBytes(unpack("ABCDEFGHIJK"));
        byte[] b = cl.readBytes(6);
        assertEquals("ABCDEF", pack(b));
        // readPos should be 6, writePos 0

        cl.writeBytes(unpack("12")); // buffer should be: 12????GHIJK
        assertEquals(7, cl.getUsedBytes());

        cl.setCapacityBytes(7);

        assertEquals(7, cl.getUsedBytes());
        assertEquals("GHIJK12", pack(cl.readBytes(7)));
    }

    @Test
    public void replayTrivial() throws IOException {
        // replay everything, and let the caller decide?
        CircularByteLog cl = new CircularByteLog(new File(logfile), 20);
        cl.writeBytes(unpack("ABCDEFGHIJKLMNOPQRS"));
        byte[] snap = cl.snapshotBytes();
        assertEquals("ABCDEFGHIJKLMNOPQRS", pack(snap));
        assertEquals(19, cl.getUsedBytes());
    }

    @Test
    public void replayWrapped() throws IOException {
        // replay everything, and let the caller decide?
        CircularByteLog cl = new CircularByteLog(new File(logfile), 28);
        cl.writeBytes(unpack("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        cl.writeBytes(unpack("1234567890"));
        byte[] snap = cl.snapshotBytes();
        assertEquals("IJKLMNOPQRSTUVWXYZ1234567890", pack(snap));
        assertEquals(28, cl.getUsedBytes());
    }
}