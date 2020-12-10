package com.cdot.ping;

import com.cdot.ping.samplers.CircularByteLog;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class CircularByteLogTest {

    @Before
    public void killLogFile() {
        new File("log.test").delete();
    }

    @Test
    public void simple() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File("log.test"), 100);
        assertEquals(100, cl.getCapacityBytes());
        byte[] blah = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
        cl.write(blah);
        assertEquals(blah.length, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
        byte[] s = cl.read(blah.length);
        for (int i = 0; i < blah.length; i++)
            assertEquals(blah[i], s[i], 0);
        assertEquals(0, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
    }

    @Test
    public void reopen() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File("log.test"), 100);
        assertEquals(100, cl.getCapacityBytes());
        byte[] blah = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K' };
        cl.write(blah);
        cl.close();
        cl = new CircularByteLog(new File("log.test"));
        byte[] s = cl.read(blah.length);
        for (int i = 0; i < blah.length; i++)
            assertEquals(blah[i], s[i], 0);
        assertEquals(0, cl.getUsedBytes());
        assertEquals(100, cl.getCapacityBytes());
    }

    @Test
    public void wrap() throws IOException {
        CircularByteLog cl = new CircularByteLog(new File("log.test"), 20);
        byte[] blah = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K' }; // 11 bytes
        byte[] blah2 = { 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V' }; // 11 bytes
        cl.write(blah);
        cl.write(blah2);
        assertEquals(20, cl.getUsedBytes());
        byte[] b = cl.read(cl.getUsedBytes());
        assertEquals(20, b.length);
        int i;
        for (i = 0; i < 20; i++) {
            byte j = (byte)('C' + i);
            assertEquals(j, b[i]);
        }
    }
}