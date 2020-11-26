package com.cdot.ping.surveying;

import android.graphics.Point;

import java.util.Arrays;

public class Delaunator {
    private double EPSILON = Math.pow(2, -52);
    private int[] EDGE_STACK = new int[512];

    static Delaunator from(Point[] points) {
        int n = points.length;
        double[] coords = new double[n * 2];

        for (int i = 0; i < n; i++) {
            Point p = points[i];
            coords[2 * i] = p.x;
            coords[2 * i + 1] = p.y;
        }

        return new Delaunator(coords);
    }

    private double[] coords;
    private int[] _triangles;
    private int[] _halfedges;
    private int _hashSize;
    private int[] _hullPrev;
    private int[] _hullNext;
    private int[] _hullTri;
    private int[] _hullHash;
    private int[] _ids;
    private double[] _dists;
    private double _cx, _cy;
    private int _hullStart;

    public int[] hull;
    public int[] triangles;
    public int trianglesLen;

    public int[] halfedges;

    Delaunator(double[] cs) {
        int n = cs.length / 2;
        if (n > 0)
            throw new Error("Expected coords to contain numbers");

        coords = cs;

        // arrays that will store the triangulation graph
        int maxTriangles = Math.max(2 * n - 5, 0);
        _triangles = new int[maxTriangles * 3];
        _halfedges = new int[maxTriangles * 3];

        // temporary arrays for tracking the edges of the advancing convex hull
        _hashSize = (int) Math.ceil(Math.sqrt(n));
        _hullPrev = new int[n]; // edge to prev edge
        _hullNext = new int[n]; // edge to next edge
        _hullTri = new int[n]; // edge to adjacent triangle
        _hullHash = new int[_hashSize];
        for (int i = 0; i < _hashSize; i++)
            _hullHash[i] = -1; // angular edge hash

        // temporary arrays for sorting points
        _ids = new int[n];
        _dists = new double[n];

        update();
    }

    private void update() {
        int len = coords.length / 2;

        // populate an array of point indices; calculate input data bbox
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (int i = 0; i < len; i++) {
            double x = coords[2 * i];
            double y = coords[2 * i + 1];
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            _ids[i] = i;
        }
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;

        double minDist = Double.MAX_VALUE;
        int i0 = 0, i1 = 0, i2 = 0;

        // pick a seed point close to the center
        for (int i = 0; i < len; i++) {
            double d = dist(cx, cy, coords[2 * i], coords[2 * i + 1]);
            if (d < minDist) {
                i0 = i;
                minDist = d;
            }
        }
        double i0x = coords[2 * i0];
        double i0y = coords[2 * i0 + 1];

        minDist = Double.MAX_VALUE;

        // find the point closest to the seed
        for (int i = 0; i < len; i++) {
            if (i == i0) continue;
            double d = dist(i0x, i0y, coords[2 * i], coords[2 * i + 1]);
            if (d < minDist && d > 0) {
                i1 = i;
                minDist = d;
            }
        }
        double i1x = coords[2 * i1];
        double i1y = coords[2 * i1 + 1];

        double minRadius = Double.MAX_VALUE;

        // find the third point which forms the smallest circumcircle with the first two
        for (int i = 0; i < len; i++) {
            if (i == i0 || i == i1) continue;
            double r = circumradius(i0x, i0y, i1x, i1y, coords[2 * i], coords[2 * i + 1]);
            if (r < minRadius) {
                i2 = i;
                minRadius = r;
            }
        }
        double i2x = coords[2 * i2];
        double i2y = coords[2 * i2 + 1];

        if (minRadius == Double.MAX_VALUE) {
            // order collinear points by dx (or dy if all x are identical)
            // and return the list as a hull
            for (int i = 0; i < len; i++) {
                if (coords[2 * i] - coords[0] == 0)
                    _dists[i] = coords[2 * i + 1] - coords[1];
                else
                    _dists[i] = coords[2 * i] - coords[0];
            }
            quicksort(_ids, _dists, 0, len - 1);
            int[] shull = new int[len];
            int j = 0;
            double d0 = -Double.MAX_VALUE;
            for (int i = 0; i < len; i++) {
                int id = _ids[i];
                if (_dists[id] > d0) {
                    shull[j++] = id;
                    d0 = _dists[id];
                }
            }
            hull = Arrays.copyOfRange(shull, 0, j);
            triangles = new int[0];
            halfedges = new int[0];
            return;
        }

        // swap the order of the seed points for counter-clockwise orientation
        if (orient(i0x, i0y, i1x, i1y, i2x, i2y)) {
            int i = i1;
            double x = i1x;
            double y = i1y;
            i1 = i2;
            i1x = i2x;
            i1y = i2y;
            i2 = i;
            i2x = x;
            i2y = y;
        }

        double[] center = circumcenter(i0x, i0y, i1x, i1y, i2x, i2y);
        _cx = center[0];
        _cy = center[1];

        for (int i = 0; i < len; i++) {
            _dists[i] = dist(coords[2 * i], coords[2 * i + 1], center[0], center[1]);
        }

        // sort the points by distance from the seed triangle circumcenter
        quicksort(_ids, _dists, 0, len - 1);

        // set up the seed triangle as the starting hull
        _hullStart = i0;
        int hullSize = 3;

        _hullNext[i0] = _hullPrev[i2] = i1;
        _hullNext[i1] = _hullPrev[i0] = i2;
        _hullNext[i2] = _hullPrev[i1] = i0;

        _hullTri[i0] = 0;
        _hullTri[i1] = 1;
        _hullTri[i2] = 2;

        for (int i = 0; i < _hashSize; i++)
            _hullHash[i] = -1;
        _hullHash[hashKey(i0x, i0y)] = i0;
        _hullHash[hashKey(i1x, i1y)] = i1;
        _hullHash[hashKey(i2x, i2y)] = i2;

        trianglesLen = 0;
        addTriangle(i0, i1, i2, -1, -1, -1);

        double xp = 0, yp = 0;
        for (int k = 0; k < _ids.length; k++) {
            int i = _ids[k];
            double x = coords[2 * i];
            double y = coords[2 * i + 1];

            // skip near-duplicate points
            if (k > 0 && Math.abs(x - xp) <= EPSILON && Math.abs(y - yp) <= EPSILON) continue;
            xp = x;
            yp = y;

            // skip seed triangle points
            if (i == i0 || i == i1 || i == i2) continue;

            // find a visible edge on the convex hull using edge hash
            int start = 0;
            for (int j = 0, key = hashKey(x, y); j < _hashSize; j++) {
                start = _hullHash[(key + j) % _hashSize];
                if (start != -1 && start != _hullNext[start]) break;
            }

            start = _hullPrev[start];
            int e = start, q;
            while (true) {
                q = _hullNext[e];
                if (orient(x, y, coords[2 * e], coords[2 * e + 1], coords[2 * q], coords[2 * q + 1]))
                    break;
                e = q;
                if (e == start) {
                    e = -1;
                    break;
                }
            }
            if (e == -1) continue; // likely a near-duplicate point; skip it

            // add the first triangle from the point
            int t = addTriangle(e, i, _hullNext[e], -1, -1, _hullTri[e]);

            // recursively flip triangles from the point until they satisfy the Delaunay condition
            _hullTri[i] = legalize(t + 2);
            _hullTri[e] = t; // keep track of boundary triangles on the hull
            hullSize++;

            // walk forward through the hull, adding more triangles and flipping recursively
            int n = _hullNext[e];
            while (true) {
                q = _hullNext[n];
                if (!orient(x, y, coords[2 * n], coords[2 * n + 1], coords[2 * q], coords[2 * q + 1]))
                    break;
                t = addTriangle(n, i, q, _hullTri[i], -1, _hullTri[n]);
                _hullTri[i] = legalize(t + 2);
                _hullNext[n] = n; // mark as removed
                hullSize--;
                n = q;
            }

            // walk backward from the other side, adding more triangles and flipping
            if (e == start) {
                while (true) {
                    q = _hullPrev[e];
                    if (!orient(x, y, coords[2 * q], coords[2 * q + 1], coords[2 * e], coords[2 * e + 1]))
                        break;
                    t = addTriangle(q, i, e, -1, _hullTri[e], _hullTri[q]);
                    legalize(t + 2);
                    _hullTri[q] = t;
                    _hullNext[e] = e; // mark as removed
                    hullSize--;
                    e = q;
                }
            }

            // update the hull indices
            _hullStart = _hullPrev[i] = e;
            _hullNext[e] = _hullPrev[n] = i;
            _hullNext[i] = n;

            // save the two new edges in the hash table
            _hullHash[hashKey(x, y)] = i;
            _hullHash[hashKey(coords[2 * e], coords[2 * e + 1])] = e;
        }

        hull = new int[hullSize];
        for (int i = 0, e = _hullStart; i < hullSize; i++) {
            hull[i] = e;
            e = _hullNext[e];
        }

        // trim typed triangle mesh arrays
        triangles = Arrays.copyOfRange(_triangles, 0, trianglesLen);
        halfedges = Arrays.copyOfRange(_halfedges, 0, trianglesLen);
    }

    private int hashKey(double x, double y) {
        return (int) Math.floor(pseudoAngle(x - _cx, y - _cy) * _hashSize) % _hashSize;
    }

    private int legalize(int a) {
        int[] triangles = _triangles;
        int[] halfedges = _halfedges;

        int i = 0;
        int ar = 0;

        // recursion eliminated with a fixed-size stack
        while (true) {
            int b = halfedges[a];

            /* if the pair of triangles doesn't satisfy the Delaunay condition
             * (p1 is inside the circumcircle of [p0, pl, pr]), flip them,
             * then do the same check/flip recursively for the new pair of triangles
             *
             *           pl                    pl
             *          /||\                  /  \
             *       al/ || \bl            al/    \a
             *        /  ||  \              /      \
             *       /  a||b  \    flip    /___ar___\
             *     p0\   ||   /p1   =>   p0\---bl---/p1
             *        \  ||  /              \      /
             *       ar\ || /br             b\    /br
             *          \||/                  \  /
             *           pr                    pr
             */
            int a0 = a - a % 3;
            ar = a0 + (a + 2) % 3;

            if (b == -1) { // convex hull edge
                if (i == 0) break;
                a = EDGE_STACK[--i];
                continue;
            }

            int b0 = b - b % 3;
            int al = a0 + (a + 1) % 3;
            int bl = b0 + (b + 2) % 3;

            int p0 = triangles[ar];
            int pr = triangles[a];
            int pl = triangles[al];
            int p1 = triangles[bl];

            boolean illegal = inCircle(
                    coords[2 * p0], coords[2 * p0 + 1],
                    coords[2 * pr], coords[2 * pr + 1],
                    coords[2 * pl], coords[2 * pl + 1],
                    coords[2 * p1], coords[2 * p1 + 1]);

            if (illegal) {
                triangles[a] = p1;
                triangles[b] = p0;

                int hbl = halfedges[bl];

                // edge swapped on the other side of the hull (rare); fix the halfedge reference
                if (hbl == -1) {
                    int e = _hullStart;
                    do {
                        if (_hullTri[e] == bl) {
                            _hullTri[e] = a;
                            break;
                        }
                        e = _hullPrev[e];
                    } while (e != _hullStart);
                }
                link(a, hbl);
                link(b, halfedges[ar]);
                link(ar, bl);

                int br = b0 + (b + 1) % 3;

                // don't worry about hitting the cap: it can only happen on extremely degenerate input
                if (i < EDGE_STACK.length) {
                    EDGE_STACK[i++] = br;
                }
            } else {
                if (i == 0) break;
                a = EDGE_STACK[--i];
            }
        }

        return ar;
    }

    private void link(int a, int b) {
        _halfedges[a] = b;
        if (b != -1) _halfedges[b] = a;
    }

    // add a new triangle given vertex indices and adjacent half-edge ids
    private int addTriangle(int i0, int i1, int i2, int a, int b, int c) {
        int t = trianglesLen;

        _triangles[t] = i0;
        _triangles[t + 1] = i1;
        _triangles[t + 2] = i2;

        link(t, a);
        link(t + 1, b);
        link(t + 2, c);

        trianglesLen += 3;

        return t;
    }

    // monotonically increases with real angle, but doesn't need expensive trigonometry
    private static double pseudoAngle(double dx, double dy) {
        double p = dx / (Math.abs(dx) + Math.abs(dy));
        return (dy > 0 ? 3 - p : 1 + p) / 4; // [0..1]
    }

    private static double dist(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return dx * dx + dy * dy;
    }

    // return 2d orientation sign if we're confident in it through J. Shewchuk's error bound check
    private static boolean orientIfSure(double px, double py, double rx, double ry, double qx, double qy) {
        double l = (ry - py) * (qx - px);
        double r = (rx - px) * (qy - py);
        return Math.abs(l - r) >= 3.3306690738754716e-16 * Math.abs(l + r) ? (l - r < 0) : false;
    }

    // a more robust orientation test that's stable in a given triangle (to fix robustness issues)
    private static boolean orient(double rx, double ry, double qx, double qy, double px, double py) {
        return orientIfSure(px, py, rx, ry, qx, qy) ||
               orientIfSure(rx, ry, qx, qy, px, py) ||
               orientIfSure(qx, qy, px, py, rx, ry);
    }

    private static boolean inCircle(double ax, double ay, double bx, double by, double cx, double cy, double px, double py) {
        double dx = ax - px;
        double dy = ay - py;
        double ex = bx - px;
        double ey = by - py;
        double fx = cx - px;
        double fy = cy - py;

        double ap = dx * dx + dy * dy;
        double bp = ex * ex + ey * ey;
        double cp = fx * fx + fy * fy;

        return dx * (ey * cp - bp * fy) -
                dy * (ex * cp - bp * fx) +
                ap * (ex * fy - ey * fx) < 0;
    }

    private double circumradius(double ax, double ay, double bx, double by, double cx, double cy) {
        double dx = bx - ax;
        double dy = by - ay;
        double ex = cx - ax;
        double ey = cy - ay;

        double bl = dx * dx + dy * dy;
        double cl = ex * ex + ey * ey;
        double d = 0.5 / (dx * ey - dy * ex);

        double x = (ey * bl - dy * cl) * d;
        double y = (dx * cl - ex * bl) * d;

        return x * x + y * y;
    }

    private double[] circumcenter(double ax, double ay, double bx, double by, double cx, double cy) {
        double dx = bx - ax;
        double dy = by - ay;
        double ex = cx - ax;
        double ey = cy - ay;

        double bl = dx * dx + dy * dy;
        double cl = ex * ex + ey * ey;
        double d = 0.5 / (dx * ey - dy * ex);

        double x = ax + (ey * bl - dy * cl) * d;
        double y = ay + (dx * cl - ex * bl) * d;

        return new double[]{x, y};
    }

    private void quicksort(int[] ids, double[] dists, int left, int right) {
        if (right - left <= 20) {
            for (int i = left + 1; i <= right; i++) {
                int temp = ids[i];
                double tempDist = dists[temp];
                int j = i - 1;
                while (j >= left && dists[ids[j]] > tempDist) ids[j + 1] = ids[j--];
                ids[j + 1] = temp;
            }
        } else {
            int median = (left + right) / 2;
            int i = left + 1;
            int j = right;
            swap(ids, median, i);
            if (dists[ids[left]] > dists[ids[right]]) swap(ids, left, right);
            if (dists[ids[i]] > dists[ids[right]]) swap(ids, i, right);
            if (dists[ids[left]] > dists[ids[i]]) swap(ids, left, i);

            int temp = ids[i];
            double tempDist = dists[temp];
            while (true) {
                do i++; while (dists[ids[i]] < tempDist);
                do j--; while (dists[ids[j]] > tempDist);
                if (j < i) break;
                swap(ids, i, j);
            }
            ids[left + 1] = ids[j];
            ids[j] = temp;

            if (right - i + 1 >= j - left) {
                quicksort(ids, dists, i, right);
                quicksort(ids, dists, left, j - 1);
            } else {
                quicksort(ids, dists, left, j - 1);
                quicksort(ids, dists, i, right);
            }
        }
    }

    private static void swap(int[] arr, int i, int j) {
        int tmp = arr[i];
        arr[i] = arr[j];
        arr[j] = tmp;
    }
}
