# Ping

Low-cost sonar, using an Erchang Fish Finder.

## Usage
To install, click on the (Releases)[https://github.com/cdot/Mr-Lister/releases] link on the 
right of the screen to see the binary releases. Download the APK file to your device and open it to install. Android
must be configured to allow installation from Unknown Sources.

(Read this to find out more about what Google considers as 'Unknown')[https://www.androidcentral.com/unknown-sources]

When you start the app it will scan for a compatible sonar device (you can shortcut this process
by pairing your device with the sonar, but it isn't essential). When it finds a compatible device
it will try to connect and start sampling. Samples are recorded on a moving trace. Note that samples
 are only recorded when there is a detectable change.

Settings are explained in the settings screen. The GPX button is used to save the trace to a GPX
file of your choice. This file can be loaded into GPS software, or used in my "Surveying" project
to build a 3D map of the bottom.

## Background & Technical Detail

This work was in support of a project to build a bathygraphic map of a
sites used by SCUBA divers from (our dive club)[http://harfordscuba.co.uk].
 
Previously surveying had been done using a GPS tracker attached to a buoy, while a diver
below noted times and depths below the buoy on a slate. This is a slow and
error-prone process, and I wanted a better solution.

There are a number of low cost sonar fish-finder devices on the market; they are primarily designed for the
angling community to help locate fish. A few have bluetooth
interfaces that allow them to talk to an Android phone. I thought such
a device could be attached to the buoy alongside a phone to give
continuous depth information, while the phone provided the location as
the buoy moves through the water.

So I purchased one of the cheapest of these devices from Ebay, an
Erchang device that normally uses the ("Erchang Fish Helper" software
freely available from Google Play)[https://play.google.com/store/apps/details?id=com.fish.fishhint].

I had hoped the software might provide a loggable depth trace, but it
proved to be quite specific to the needs of the angling community. So
I reverse-engineered the simple device protocol and built this software. It
is a simple app that focuses on logging the depth data returned by the
device, in coordination with location data as determined by the
Android device GPS. Once the app is started and connected to a device, it logs continuously
to a buffer that can be selectively dumped to a standard GPX XML file for later analysis. A simple
display gives a view of the sonar data as it comes in.

The GPX files can be used directly in my "Surveying" project to incrementally build up a 3D picture of the
bottom in the surveyed area.

## The Device

+ Relevant specifications of the Erchang sensor:
+ Depth range: 0.6-36m
+ Sonar frequency: 125kHz
+ Approximate sampling rate: 8Hz
+ Sonar beam angle: 90 degrees
+ Bluetooth interface: MicroChip IS1678S-152 (LE and Classic)

## The Protocol

The protocol was reverse-engineered by watching bluetooth packets sent
to/from the device by the Erchang "Fish Helper" software. This
software uses classic BR/EDR bluetooth to communicate with the device,
but LE works just as well. To explore the protocol, I built the
"PingTest" software to simulate a sonar device and explore the
reactions of the Erchang sofware to what it produces.

The sonar device is configured by setting the required sensitivity,
noise filtering, and range. There may be more settings, but this is
all the Erchang software uses (and is enough for my purpose).

Samples are delivered at an approximate rate of 8Hz, and contain:
+ an indication of whether the contacts are wet or not
+ the depth, in 2 bytes (real part and fractional part)
+ a byte with the signal return strength, which is interpreted by the Erchang software as "weed". The total range of this strength is difficult to determine, but they appear to interpret it as: 30-40, "large weed", 40-50 "medium weed", and 50-60 "small weed". Any value outside these ranges is "no weed".
+ another 2-byte depth which the Erchang software interprets as the depth of a fish (or shoal of fish)
+ a nibble which indicates the strength of the fish return. This is interpreted by the Erchang software as 0 "no fish", 1 "small fish", 2 "medium fish", 3 "large fish or shoal". Any other value is interpreted as "small fish".
+ a nibble for the battery strength
+ the temperature.

I didn't bother to try and calibrate the fish detection any further as its of no interest to me.
The software is written to be easily adapted to other Bluetooth LE sonar fish-finders, so is not
tied to the Erchang device.

## The Application

The application is split into a UI and a service. The service is largely independent from the UI
and will survive even if the UI detaches (is killed) but a device is still detected.

### The UI

The UI consists of three simple screens, one for device discovery which lists
all the compatible devices, one for settings, and the main UI screen shown when
a device is connected. This has a text pane showing the status of the device and
a sonar trace which uses colour to display the depth trace.

### The Service

The service runs in the background and is responsible for sampling the sonar device,
and the location. Samples are logged to a circular buffer (queue) that can be
snapshotted as a GPX file at any time. The service will survive even when
the UI has been closed, and has to be shut down from the notification drawer. The
service will continue running and trying to connect to a sonar device, even if there
is no device in range, until you dismiss it.

The service uses Bluetooth LE to talk to the sonar by default, though there is an alternate implementation
using Bluetooth Classic also available. This is because the Android Bluetooth stack is pretty horrible, and
throws errors unpredictably. Most should be handled, but you might have more luck with the brute-force
Classic implementation.

The app will automatically scan for Bluetooth devices that offer the
service required.