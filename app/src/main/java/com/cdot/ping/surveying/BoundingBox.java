package com.cdot.ping.surveying;

public class BoundingBox {
    public Vector3 min = new Vector3();
    public Vector3 max = new Vector3();

    public BoundingBox() {
    }

    public BoundingBox(double minx, double miny, double maxx, double maxy) {
        min.x = minx;
        min.y = miny;
        max.x = maxx;
        max.y = maxy;
    }
}
