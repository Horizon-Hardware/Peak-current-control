package cn.crb.wavescope.core;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Bounded history. One row contains a whole polling frame; invalid reads are NaN gaps. */
public final class SampleBuffer {
    private final double[] times;
    private final double[][] data;
    private int head, size;
    public record Snapshot(double[] times, double[][] values) { public int size() { return times.length; } }

    public SampleBuffer(int channels, int capacity) {
        if (channels < 1 || capacity < 1) throw new IllegalArgumentException();
        times = new double[capacity]; data = new double[channels][capacity];
    }
    public synchronized void add(double seconds, double[] values) {
        if (values.length != data.length) throw new IllegalArgumentException();
        times[head] = seconds;
        for (int i = 0; i < data.length; i++) data[i][head] = Double.isFinite(values[i]) ? values[i] : Double.NaN;
        head = (head + 1) % times.length; size = Math.min(size + 1, times.length);
    }
    public synchronized void gap(double seconds) { double[] missing = new double[data.length]; Arrays.fill(missing, Double.NaN); add(seconds, missing); }
    public synchronized void clear() { head = size = 0; }
    public synchronized Snapshot snapshot() {
        double[] t = new double[size]; double[][] values = new double[data.length][size];
        int first = (head - size + times.length) % times.length;
        for (int n = 0; n < size; n++) {
            int source = (first + n) % times.length;
            t[n] = times[source];
            for (int i = 0; i < data.length; i++) values[i][n] = data[i][source];
        }
        return new Snapshot(t, values);
    }
    public static void writeCsv(Writer writer, List<String> names, Snapshot snapshot) throws IOException {
        writer.write("time_s");
        for (String name : names) writer.write(",\"" + name.replace("\"", "\"\"") + "\"");
        writer.write("\n");
        for (int n = 0; n < snapshot.size(); n++) {
            writer.write(String.format(Locale.ROOT, "%.6f", snapshot.times()[n]));
            for (int i = 0; i < names.size(); i++) {
                writer.write(",");
                double value = snapshot.values()[i][n];
                if (Double.isFinite(value)) writer.write(Double.toString(value));
            }
            writer.write("\n");
        }
    }
}
