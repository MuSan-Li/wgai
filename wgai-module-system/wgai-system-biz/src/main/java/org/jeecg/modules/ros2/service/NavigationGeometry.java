package org.jeecg.modules.ros2.service;

/** Geometry shared by localization validation and map-cell collision diagnostics. */
public final class NavigationGeometry {
    private NavigationGeometry() {}

    public static boolean finitePose(double[] p) {
        return p != null && p.length >= 3 && Double.isFinite(p[0]) && Double.isFinite(p[1]) && Double.isFinite(p[2]);
    }

    public static double wrap(double angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }

    public static double[] predict(double[] map, double[] odomReference, double[] odomNow) {
        if (!finitePose(map) || !finitePose(odomReference) || !finitePose(odomNow)) return null;
        double dx = odomNow[0] - odomReference[0], dy = odomNow[1] - odomReference[1];
        double c = Math.cos(odomReference[2]), s = Math.sin(odomReference[2]);
        double lx = c * dx + s * dy, ly = -s * dx + c * dy;
        c = Math.cos(map[2]); s = Math.sin(map[2]);
        return new double[]{map[0] + c * lx - s * ly, map[1] + s * lx + c * ly,
                wrap(map[2] + wrap(odomNow[2] - odomReference[2]))};
    }

    /** AMCL correction after subtracting actual motion reported by odometry: metres, radians. */
    public static double[] correction(double[] previousMap, double[] previousOdom, double[] currentMap, double[] currentOdom) {
        double[] expected = predict(previousMap, previousOdom, currentOdom);
        if (expected == null || !finitePose(currentMap)) return null;
        return new double[]{Math.hypot(currentMap[0] - expected[0], currentMap[1] - expected[1]),
                Math.abs(wrap(currentMap[2] - expected[2]))};
    }

    /** Convex footprint versus a whole occupied cell. Touching a cell counts as collision. */
    public static boolean cellIntersects(double[][] poly, double cx, double cy, double resolution) {
        if (separated(poly, cx, cy, resolution, 1, 0) || separated(poly, cx, cy, resolution, 0, 1)) return false;
        for (int i = 0; i < poly.length; i++) {
            double[] a = poly[i], b = poly[(i + 1) % poly.length];
            if (separated(poly, cx, cy, resolution, -(b[1] - a[1]), b[0] - a[0])) return false;
        }
        return true;
    }

    private static boolean separated(double[][] poly, double cx, double cy, double res, double ax, double ay) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double[] p : poly) { double value = p[0] * ax + p[1] * ay; min = Math.min(min, value); max = Math.max(max, value); }
        double mid = cx * ax + cy * ay, radius = res * (Math.abs(ax) + Math.abs(ay)) / 2;
        return max < mid - radius - 1e-10 || min > mid + radius + 1e-10;
    }
}
