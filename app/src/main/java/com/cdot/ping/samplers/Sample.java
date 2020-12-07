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

import android.location.Location;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Date;
import java.util.TimeZone;

public class Sample implements Parcelable {
    public boolean isDry; // contacts not conducting
    public long time;
    public double depth;
    public int strength; // Range 0..255
    public double temperature;
    public int battery; // Range 0..6
    public double fishDepth;
    public int fishStrength; // Range 0..15
    public Location location;

    Sample() {
    }

    protected Sample(Parcel in) {
        isDry = in.readByte() != 0;
        time = in.readLong();
        depth = in.readDouble();
        strength = in.readInt();
        temperature = in.readDouble();
        battery = in.readInt();
        fishDepth = in.readDouble();
        fishStrength = in.readInt();
        location = in.readParcelable(Location.class.getClassLoader());
    }

    public static final Creator<Sample> CREATOR = new Creator<Sample>() {
        @Override
        public Sample createFromParcel(Parcel in) {
            return new Sample(in);
        }

        @Override
        public Sample[] newArray(int size) {
            return new Sample[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeByte((byte) (isDry ? 1 : 0));
        parcel.writeLong(time);
        parcel.writeDouble(depth);
        parcel.writeInt(strength);
        parcel.writeDouble(temperature);
        parcel.writeInt(battery);
        parcel.writeDouble(fishDepth);
        parcel.writeInt(fishStrength);
        parcel.writeParcelable(location, 0);
    }

    public Element toGPX(Document doc) {
        Element GPX_trkpt = doc.createElementNS(GPX.NS_GPX, "trkpt");
        GPX_trkpt.setAttribute("lat", Double.toString(location.getLatitude()));
        GPX_trkpt.setAttribute("lon", Double.toString(location.getLongitude()));

        Element GPX_ele = doc.createElementNS(GPX.NS_GPX, "ele");
        GPX_ele.setTextContent(Double.toString(location.getAltitude() - (isDry ? 0 : depth)));
        GPX_trkpt.appendChild(GPX_ele);

        Element GPX_time = doc.createElementNS(GPX.NS_GPX, "time");
        Date date = new Date(time);
        GPX.ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("Z"));
        String text = GPX.ISO_DATE_FORMAT.format(date);
        GPX_time.setTextContent(text);
        GPX_trkpt.appendChild(GPX_time);

        Element GPX_extensions = doc.createElementNS(GPX.NS_GPX, "extensions");
        Element GPX_ping = doc.createElementNS(GPX.NS_PING, "ping");
        if (strength > 0)
            GPX_ping.setAttribute("strength", Integer.toString(strength));
        if (fishDepth > 0)
            GPX_ping.setAttribute("fdepth", Double.toString(fishDepth));
        if (fishStrength > 0)
            GPX_ping.setAttribute("fstrength", Integer.toString(fishStrength));
        if (location.getAccuracy() > 0)
            GPX_ping.setAttribute("hacc", Float.toString(location.getAccuracy()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (location.getVerticalAccuracyMeters() > 0)
                GPX_ping.setAttribute("vacc", Float.toString(location.getVerticalAccuracyMeters()));
        }
        GPX_extensions.appendChild(GPX_ping);
        GPX_trkpt.appendChild(GPX_extensions);
        return GPX_trkpt;
    }
}
