package com.cdot.ping;

import com.cdot.ping.samplers.CircularSampleLog;
import com.cdot.ping.samplers.Sample;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class CircularSampleLogTest {

    private static final String logfile = "samples.log";
    
    @Before
    public void killLogFile() {
        new File(logfile).delete();
    }

    @Test
    public void simple() throws IOException {
        CircularSampleLog simple = new CircularSampleLog(new File(logfile), 2);
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(0, simple.getUsedSamples());
        simple.writeSample(new Sample(1, -2, 10, 5));
        simple.writeSample(new Sample(2, -3, 15, 7));
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(2, simple.getUsedSamples());
        simple.writeSample(new Sample(3, -2, 10, 5));
        simple.writeSample(new Sample(4, -2, 10, 5));
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(2, simple.getUsedSamples());
        Sample[] ss = simple.readSamples(2);
        assertEquals(1, ss[0].latitude, 0);
        assertEquals(-2, ss[0].longitude, 0);
        assertEquals(10, ss[0].depth, 0);
        assertEquals(5, ss[0].strength, 0);
        assertEquals(2, ss[1].latitude, 0);
        assertEquals(-3, ss[0].longitude, 0);
        assertEquals(15, ss[0].depth, 0);
        assertEquals(7, ss[0].strength, 0);
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(0, simple.getUsedSamples());
    }

    @Test
    public void reopen() throws IOException {
        CircularSampleLog simple = new CircularSampleLog(new File(logfile), 2);
        simple.writeSample(new Sample(1, -2, 10, 5));
        simple.writeSample(new Sample(2, -2, 10, 5));
        simple.close();
        simple = new CircularSampleLog(new File(logfile));
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(2, simple.getUsedSamples());
        Sample ss = simple.readSample();
        assertEquals(1, ss.latitude, 0);
        assertEquals(2, ss.longitude, 0);
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(0, simple.getUsedSamples());
    }

    @Test
    public void snapshotSamples() throws IOException {
        CircularSampleLog simple = new CircularSampleLog(new File(logfile), 2);
        simple.writeSample(new Sample(1, -2, 10, 5));
        simple.writeSample(new Sample(2, -3, 15, 7));
        simple.writeSample(new Sample(3, -2, 10, 5));
        simple.writeSample(new Sample(4, -2, 10, 5));
        Sample[] ss = simple.snapshotSamples();
        assertEquals(4, simple.getUsedSamples());
        assertEquals(4, ss.length);
        assertEquals(1, ss[0].latitude, 0);
        assertEquals(-2, ss[0].longitude, 0);
        assertEquals(10, ss[0].depth, 0);
        assertEquals(5, ss[0].strength, 0);
        assertEquals(2, ss[1].latitude, 0);
        assertEquals(3, ss[2].latitude, 0);
        assertEquals(4, ss[3].latitude, 0);
    }
}
