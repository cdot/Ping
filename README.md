# Ping

This work is in support of a project to build a bathygraphic map of a quarry used by SCUBA divers
from our dive club. Previously surveying has been done using a GPS tracker attached to a buoy,
while a diver below notes depths below the buoy on a slate. This is a slow and error-prone process,
and I wanted a better solution.

There are a number of sonar fish-finder devices on the market that appeared to offer a solution;
they are primarily designed for the angling community and have limited access to depth data. A
few, however, have bluetooth interfaces that allow them to interface to an Android phone. I thought
such a device could be attached to the buoy alongside a phone to give continuous depth and location
data as the buoy moves through the water.

So I purchased one of the cheapest of these devices from Ebay, an unbranded device that normally
uses the "Erchang Fish Helper" software from Google Play
https://play.google.com/store/apps/details?id=com.fish.fishhint.

I had hoped the software might provide a loggable depth trace, but it proved to be quite specific
to the needs of the angling community. So I reverse-engineered the simple device protocol and built
Ping. This is a simple app that focuses on logging the depth data returned by the device, in
coordination with location data as determined by the device GPS. Logging is done to CSV files that
record GPS lat, long, a depth in metres, and some extra information such as the sonar signal
strength and water temperature, for later analysis. A simple display gives a view of the sonar data
as it comes in, but is not intended to be of any practical use.

The logged sample data can be used directly in my "Surveying" project to incrementally build up a
3D picture of the bottom in the surveyed area.

## The Device

Relevant specifications of the sensor:
Depth range: 0.6-36m
Sonar frequency: 125KHz
Sonar beam angle: 90 degrees
Bluetooth interface: MicroChip IS1678S-152

## The Protocol

The protocol was reverse-engineered by watching bluetooth packets sent to/from the device by the
Erchang "Fish Helper" software. This software uses classic BR/EDR bluetooth to communicate with
the device, but LE works just as well.

The device is configured by setting the required sensitivity,
noise filtering, and range. There may be more settings, but this is all the Erchang software uses.

Samples contain an indication of whether the contacts are wet or not, the depth, and what appears
to be the signal return strength (depending on the nature of the bottom, I assume). Another depth
which appears to be the depth of an intermediate return (i.e. a fish) and a byte which appears to
indicate the horizontal extent for that signal. Finally a battery strength and temperature.

## The Application
The application is split into a UI and two services. The services are largely independent from the UI
and can bring themselves into the foreground even if the UI detaches (is killed) but logging is still
active. One of the services is responsible for GPS location, and the other for talking to the sonar device.

## User Guide
The app will automatically scan for bluetooth devices that offer the service required. You can
shortcut this process by pairing your device with the sonar. Once you start logging using the "record"
button, the background services will continue logging until you turn logging off.