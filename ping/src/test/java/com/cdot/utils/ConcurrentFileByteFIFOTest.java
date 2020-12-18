package com.cdot.utils;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.BufferOverflowException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ConcurrentFileByteFIFOTest {

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
    private String pack(byte[] blah, int lim) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(blah.length, lim); i++)
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
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 100);
        assertEquals(100, cl.getCapacityBytes());
        String ts = "0123456789!";
        cl.add(unpack(ts), 0, 11);
        assertEquals(11, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
        byte[] buf = new byte[15];
        assertEquals(ts.length(), cl.remove(buf, 0, 15));
        assertEquals(ts, pack(buf, ts.length()));
        assertEquals(0, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
    }

    @Test
    public void reopen() throws IOException {
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 100);
        assertEquals(100, cl.getCapacityBytes());
        String ts = "ABCDEFGHIJK";
        byte[] blah = unpack(ts);
        cl.add(blah, 0, blah.length);
        cl.close();
        cl = new ConcurrentFileByteFIFO(new File(logfile), false);
        blah = new byte[100];
        assertEquals(ts.length(), cl.remove(blah, 0, cl.getUsedBytes()));
        assertEquals(ts, pack(blah, ts.length()));
        assertEquals(0, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
    }

    @Test
    public void wrap() throws IOException {
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 20);
        cl.add(unpack("ABCDEFGHIJK"));
        cl.add(unpack("LMNOPQRSTUV"));
        assertEquals(20, cl.getUsedBytes());
        byte[] b = new byte[20];
        assertEquals(20, cl.remove(b, 0, 20));
        assertEquals("CDEFGHIJKLMNOPQRSTUV", pack(b));
    }

    @Test
    public void overflow() throws IOException {
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 10);
        try {
            cl.add(unpack("ABCDEFGHIJKLMNOPQRSTUV"));
            fail();
        } catch (BufferOverflowException ignored) {
        }
    }

    @Test
    public void growReadBeforeWrite() throws IOException {
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 11);
        cl.add(unpack("ABCDEFGHIJK"));
        byte[] b = new byte[4];
        assertEquals(4, cl.remove(b, 0, 4));
        assertEquals("ABCD", pack(b));
        // readPos should be 4
        assertEquals(7, cl.getUsedBytes());

        cl.setCapacityBytes(13);
        assertEquals(7, cl.getUsedBytes());
        assertEquals(13, cl.getCapacityBytes());

        b = new byte[7];
        assertEquals(7, cl.remove(b, 0, cl.getUsedBytes()));
        assertEquals("EFGHIJK", pack(b));
    }

    @Test
    public void growWriteBeforeRead() throws IOException {
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 11);
        cl.add(unpack("ABCDEFGHIJK"));
        byte[] b = new byte[7];
        assertEquals(6, cl.remove(b, 0, 6));
        assertEquals("ABCDEF", pack(b, 6));
        // readPos should be 6, writePos 0

        cl.add(unpack("12")); // buffer should be: 12????GHIJK
        assertEquals(7, cl.getUsedBytes());

        cl.setCapacityBytes(13);
        assertEquals(7, cl.getUsedBytes());
        assertEquals(13, cl.getCapacityBytes());

        assertEquals(7, cl.remove(b, 0, cl.getUsedBytes()));
        assertEquals(7, b.length);
        assertEquals("GHIJK12", pack(b));
    }

    @Test
    public void growTrivial() throws IOException {
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 11);
        String a = "LMNOPQRSTUV";
        String b = "ABCDEFGHIJK";
        cl.add(unpack(a));
        cl.add(unpack(b));
        assertEquals(11, cl.getUsedBytes());
        cl.setCapacityBytes(22);
        assertEquals(11, cl.getUsedBytes());
        assertEquals(22, cl.getCapacityBytes());
        cl.add(unpack(a));
        assertEquals((a + b).length(), cl.getUsedBytes());
        byte[] buf = new byte[100];
        cl.remove(buf, 0, cl.getUsedBytes());
        assertEquals(b + a, pack(buf, (a + b).length()));
    }

    @Test
    public void shrinkReadBeforeWrite() throws IOException {
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 11);
        cl.add(unpack("ABCDEFGHIJK"));
        byte[] b = new byte[11];
        cl.remove(b, 0, 3);
        assertEquals("ABC", pack(b, 3));
        // readPos should be 3
        assertEquals(8, cl.getUsedBytes());

        cl.setCapacityBytes(5);

        // should have lost DEF
        assertEquals(5, cl.getUsedBytes());
        cl.remove(b, 0, 5);
        assertEquals("GHIJK", pack(b, 5));
    }

    @Test
    public void shrinkWriteBeforeRead() throws IOException {
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 11);
        cl.add(unpack("ABCDEFGHIJK"));
        byte[] b = new byte[7];
        assertEquals(6, cl.remove(b, 0, 6));
        assertEquals("ABCDEF", pack(b, 6));
        // readPos should be 6, writePos 0

        cl.add(unpack("123")); // buffer should be: 123???GHIJK
        assertEquals(8, cl.getUsedBytes());

        cl.setCapacityBytes(7);

        assertEquals(7, cl.getUsedBytes());
        cl.remove(b, 0, 7);
        assertEquals("HIJK123", pack(b));
    }

    @Test
    public void snapshotTrivial() throws IOException {
        // snapshot a simple buffer with no wrap
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 20);
        cl.add(unpack("ABCDEFGHIJKLMNOPQRS"));
        byte[] snap = new byte[cl.getUsedBytes()];

        assertEquals(snap.length, cl.snapshot(snap, 0, snap.length));
        assertEquals("ABCDEFGHIJKLMNOPQRS", pack(snap));

        assertEquals(5, cl.snapshot(snap, 0, 5));
        assertEquals("OPQRSFGHIJKLMNOPQRS", pack(snap));

        assertEquals(5, cl.snapshot(snap, 5, 5));
        assertEquals("OPQRSOPQRSKLMNOPQRS", pack(snap));

        assertEquals(19, cl.getUsedBytes());
    }

    @Test
    public void snapshotWrapped() throws IOException {
        // snapshot a buffer that has been wrapped at "I"
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 28);
        cl.add(unpack("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        cl.add(unpack("1234567890"));
        byte[] snap = new byte[cl.getUsedBytes()];
        assertEquals(snap.length, cl.snapshot(snap, 0, snap.length));
        assertEquals("IJKLMNOPQRSTUVWXYZ1234567890", pack(snap));
        assertEquals(28, cl.getUsedBytes());
    }

    @Test
    public void snapshotTooWee() throws IOException {
        // snapshot a buffer that is shorter than wanted
        ConcurrentFileByteFIFO cl = new ConcurrentFileByteFIFO(new File(logfile), 10);
        cl.add(unpack("ABCD"));
        byte[] snap = new byte[cl.getCapacityBytes()];
        assertEquals(4, cl.snapshot(snap, 0, snap.length));
        assertEquals("ABCD", pack(snap, 4));
    }
}