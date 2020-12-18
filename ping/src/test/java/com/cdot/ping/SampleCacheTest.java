package com.cdot.ping;

import com.cdot.ping.samplers.SampleCache;
import com.cdot.ping.samplers.Sample;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class SampleCacheTest {

    private static final String logfile = "samples.log";
    
    @Before
    public void killLogFile() {
        new File(logfile).delete();
    }

    @Test
    public void simple() throws IOException {
        SampleCache simple = new SampleCache(new File(logfile), 2);
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(0, simple.getUsedSamples());
        simple.add(new Sample(0, 1, -2, 10, 5));
        simple.add(new Sample(1000, 2, -3, 15, 7));
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(2, simple.getUsedSamples());
        simple.add(new Sample(2000, 3, -2, 10, 5));
        simple.add(new Sample(3000, 4, -3, 109, 7));
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(2, simple.getUsedSamples());
        Sample[] ss = simple.removeSamples(2);

        assertEquals(2000, ss[0].time);
        assertEquals(3, ss[0].latitude, 0);
        assertEquals(-2, ss[0].longitude, 0);
        assertEquals(10, ss[0].depth, 0);
        assertEquals(5, ss[0].strength);

        assertEquals(3000, ss[1].time);
        assertEquals(4, ss[1].latitude, 0);
        assertEquals(-3, ss[1].longitude, 0);
        assertEquals(109, ss[1].depth, 0);
        assertEquals(7, ss[1].strength);

        assertEquals(2, simple.getCapacitySamples());
        assertEquals(0, simple.getUsedSamples());
    }

    @Test
    public void reopen() throws IOException {
        SampleCache simple = new SampleCache(new File(logfile), 2);
        simple.add(new Sample(1000, 1, 2, 10, 5));
        simple.add(new Sample(2000, 2, -2, 10, 5));
        simple.close();
        simple = new SampleCache(new File(logfile), false);
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(2, simple.getUsedSamples());
        Sample ss = simple.removeSample();
        assertEquals(1, ss.latitude, 0);
        assertEquals(2, ss.longitude, 0);
        assertEquals(2, simple.getCapacitySamples());
        assertEquals(1, simple.getUsedSamples());
    }

    @Test
    public void snapshotSamples() throws IOException {
        SampleCache simple = new SampleCache(new File(logfile), 20);
        simple.add(new Sample(10000, 1, -2, 10, 5));
        simple.add(new Sample(11000, 2, -3, 15, 7));
        simple.add(new Sample(12000, 3, -2, 10, 5));
        simple.add(new Sample(13000, 4, -2, 10, 5));
        Sample[] ss = new Sample[4];
        simple.snapshot(ss, 0, 4);
        assertEquals(4, simple.getUsedSamples());
        assertEquals(1, ss[0].latitude, 0);
        assertEquals(-2, ss[0].longitude, 0);
        assertEquals(10, ss[0].depth, 0);
        assertEquals(5, ss[0].strength, 0);
        assertEquals(2, ss[1].latitude, 0);
        assertEquals(3, ss[2].latitude, 0);
        assertEquals(4, ss[3].latitude, 0);
    }
}
