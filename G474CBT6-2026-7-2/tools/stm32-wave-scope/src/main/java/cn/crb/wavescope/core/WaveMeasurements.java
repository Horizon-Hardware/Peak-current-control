package cn.crb.wavescope.core;

public final class WaveMeasurements {
    private WaveMeasurements() { }
    public record Channel(double minimum, double maximum, double peakToPeak, double a, double b, double delta,
                          int samples, int missing) { }
    public record Result(boolean interval, double a, double b, Channel[] channels) {
        public double timeDifference() { return Math.abs(b - a); }
    }
    public static int nearest(double[] times, double target) {
        if (times.length == 0) return -1;
        int low = 0, high = times.length;
        while (low < high) { int mid = (low + high) >>> 1; if (times[mid] < target) low = mid + 1; else high = mid; }
        if (low == times.length) return low - 1;
        if (low == 0) return 0;
        return target - times[low - 1] <= times[low] - target ? low - 1 : low;
    }
    public static Result calculate(SampleBuffer.Snapshot data, boolean interval, double a, double b) {
        int ai = interval ? nearest(data.times(), a) : -1, bi = interval ? nearest(data.times(), b) : -1;
        double ta = ai < 0 ? Double.NaN : data.times()[ai], tb = bi < 0 ? Double.NaN : data.times()[bi];
        int first = interval ? Math.min(ai, bi) : 0, last = interval ? Math.max(ai, bi) + 1 : data.size();
        Channel[] channels = new Channel[data.values().length];
        for (int c = 0; c < channels.length; c++) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            int count = 0, missing = 0;
            for (int i = Math.max(0, first); i < last; i++) {
                double value = data.values()[c][i];
                if (!Double.isFinite(value)) { missing++; continue; }
                min = Math.min(min, value); max = Math.max(max, value); count++;
            }
            if (count == 0) min = max = Double.NaN;
            double va = ai < 0 ? Double.NaN : data.values()[c][ai], vb = bi < 0 ? Double.NaN : data.values()[c][bi];
            channels[c] = new Channel(min, max, max - min, va, vb, vb - va, count, missing);
        }
        return new Result(interval, ta, tb, channels);
    }
}
