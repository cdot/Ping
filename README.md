# Ping

This work is in support of a project to build a bathygraphic map of a quarry, used by SCUBA divers.
Previously surveying has been done using a GPS tracker attached to a buoy, while a diver below
notes depths below the buoy. This is a slow and error-prone process, and I wanted a better approach.

There are a number of sonar fish-finder devices on the market that appeared to offer a solution;
they are primarily designed for the angling community and have limited access to depth data. A
few, however, have bluetooth interfaces that allow them to interface to an Android phone. Such a
device could easily be attached to the buoy alongside a phone to give continuous depth and location
data as the buoy moves through the water.

So I purchased one of the cheapest of these devices, an unbranded device that uses the "Erchang Fish Helper"
software from Google Play https://play.google.com/store/apps/details?id=com.fish.fishhint.

The software proved to be fairly specific to the needs of the angling community, so I reverse-
engineered the protocol and built Ping. This is a simple app that focuses on logging the depth data
returned by the device, in coordination with location data as determined by the device GPS. Logging
is done to a CSV file that records GPS lat, long, a depth in metres, and some extra information such
as the sonar signal strength and water temperature.
