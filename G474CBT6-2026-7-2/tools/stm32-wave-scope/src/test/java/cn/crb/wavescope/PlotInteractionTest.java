package cn.crb.wavescope;

import cn.crb.wavescope.core.SampleBuffer;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/** Uses real Swing mouse events and known data; no probe or running firmware is needed. */
public final class PlotInteractionTest {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++; if (!condition) throw new AssertionError(label);
    }
    private static void mouse(WavePlot plot, int id, int x, int y, int clicks) {
        int button = id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED || id == MouseEvent.MOUSE_EXITED
                ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
        plot.dispatchEvent(new MouseEvent(plot, id, System.currentTimeMillis(), 0, x, y, clicks, false, button));
    }
    private static void click(WavePlot plot, int x, int count) {
        mouse(plot, MouseEvent.MOUSE_PRESSED, x, 100, count);
        mouse(plot, MouseEvent.MOUSE_RELEASED, x, 100, count);
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WavePlot plot = new WavePlot(); plot.setFont(new Font("Dialog", Font.PLAIN, 12)); plot.setSize(1000, 480);
            int[] paused = {0}, live = {0};
            plot.setInteractionListeners(r -> { }, () -> paused[0]++, () -> live[0]++);
            double[] times = new double[101]; double[][] values = new double[2][101];
            for (int i = 0; i < times.length; i++) { times[i] = i / 10.0; values[0][i] = times[i] * 2; values[1][i] = times[i] * -3; }
            values[1][90] = Double.NaN;
            SampleBuffer.Snapshot data = new SampleBuffer.Snapshot(times, values);
            plot.showData(data, List.of("voltage", "current"), true, 2);
            mouse(plot, MouseEvent.MOUSE_MOVED, 533, 120, 0);
            check(plot.cursorReading() != null && plot.cursorReading().seconds() == 9 && plot.cursorReading().values()[0] == 18,
                    "hover reads the nearest real sample immediately");
            check(Double.isNaN(plot.cursorReading().values()[1]), "hover preserves failed sample gaps");
            click(plot, 533, 1);
            WavePlot.CursorReading pinned = plot.cursorReading();
            check(pinned.pinned() && paused[0] == 1, "click pins the entire sampled column and pauses only the view");
            mouse(plot, MouseEvent.MOUSE_MOVED, 700, 120, 0);
            mouse(plot, MouseEvent.MOUSE_EXITED, -1, -1, 0);
            check(plot.cursorReading().seconds() == 9 && plot.cursorReading().values()[0] == 18, "pinned values survive moving the mouse away");
            double originalStart = plot.visibleStart();
            mouse(plot, MouseEvent.MOUSE_PRESSED, 400, 100, 1);
            mouse(plot, MouseEvent.MOUSE_DRAGGED, 700, 100, 0);
            mouse(plot, MouseEvent.MOUSE_RELEASED, 700, 100, 1);
            check(plot.visibleStart() < originalStart && plot.cursorReading().seconds() == 9, "drag pans time without moving the pinned sample");
            mouse(plot, MouseEvent.MOUSE_PRESSED, 400, 100, 1);
            mouse(plot, MouseEvent.MOUSE_DRAGGED, 20000, 100, 0);
            mouse(plot, MouseEvent.MOUSE_RELEASED, 20000, 100, 1);
            check(plot.visibleStart() == 0, "drag is clamped to retained history");
            plot.dispatchEvent(new MouseWheelEvent(plot, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, 500, 120, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -2));
            check(plot.visibleSpan() < 2 && plot.visibleSpan() > 0, "wheel zooms the time axis");
            click(plot, 500, 2);
            check(live[0] == 1 && plot.cursorReading() == null && plot.visibleSpan() == 2 && plot.visibleStart() == 8,
                    "double click releases the cursor and restores live tracking");
            click(plot, 533, 1);
            double[] nextTimes = {19, 20}; double[][] nextValues = {{38, 40}, {57, 60}};
            plot.showData(new SampleBuffer.Snapshot(nextTimes, nextValues), List.of("voltage", "current"), false, 2);
            check(plot.cursorReading().seconds() == 9 && plot.cursorReading().values()[0] == 18,
                    "pinned column owns its values instead of a rolling buffer index");
            plot.resumeLive();
            check(plot.visibleStart() == 19 && plot.cursorReading() == null, "live view follows the new retained data");
            plot.showData(data, List.of("voltage", "current"), true, 2);
            plot.setMeasurementEnabled(true);
            check(plot.measurement().interval() && plot.measurement().a() == 8.7 && plot.measurement().b() == 9.3,
                    "two measurement cursors snap to retained sample timestamps");
            int ax = 85 + (int)((plot.measurement().a() - plot.visibleStart()) / plot.visibleSpan() * 895);
            mouse(plot, MouseEvent.MOUSE_PRESSED, ax, 100, 1);
            mouse(plot, MouseEvent.MOUSE_DRAGGED, 533, 100, 0);
            mouse(plot, MouseEvent.MOUSE_RELEASED, 533, 100, 1);
            check(plot.measurement().a() == 9 && Math.abs(plot.measurement().timeDifference() - 0.3) < 1e-9,
                    "dragging cursor A updates measured delta time");
            check(plot.measurement().channels()[0].minimum() == 18 && plot.measurement().channels()[0].maximum() == 18.6
                    && Double.isNaN(plot.measurement().channels()[1].a()), "cursor interval extrema and failed endpoint remain honest");
            int bx = 85 + (int)((plot.measurement().b() - plot.visibleStart()) / plot.visibleSpan() * 895);
            mouse(plot, MouseEvent.MOUSE_PRESSED, bx, 100, 1);
            mouse(plot, MouseEvent.MOUSE_DRAGGED, 350, 100, 0);
            mouse(plot, MouseEvent.MOUSE_RELEASED, 350, 100, 1);
            check(plot.measurement().b() < plot.measurement().a() && plot.measurement().timeDifference() > 0,
                    "cursor B may cross A without breaking interval statistics");
            BufferedImage image = new BufferedImage(1000, 480, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); plot.printAll(g); g.dispose();
            try { ImageIO.write(image, "png", Path.of(args[0]).toFile()); } catch (Exception ex) { throw new RuntimeException(ex); }
            plot.clear();
            check(plot.cursorReading() == null, "clear removes stale cursor values");
            check(!plot.measurement().interval(), "clear also releases measurement cursors");
        });
        java.util.concurrent.atomic.AtomicReference<WavePlot> ratePlot = new java.util.concurrent.atomic.AtomicReference<>();
        int[] statsUpdates = {0};
        SwingUtilities.invokeAndWait(() -> {
            WavePlot plot = new WavePlot(); ratePlot.set(plot);
            plot.setMeasurementListener(r -> statsUpdates[0]++);
            for (int i = 0; i < 30; i++) {
                plot.showData(new SampleBuffer.Snapshot(new double[]{i}, new double[][]{{i}}), List.of("value"), true, 15);
            }
            check(statsUpdates[0] == 1, "rapid live snapshots do not repeatedly scan history or repaint the statistics table");
        });
        Thread.sleep(550);
        SwingUtilities.invokeAndWait(() -> {
            WavePlot plot = ratePlot.get(); plot.refreshMeasurementsIfDue();
            check(statsUpdates[0] == 2 && plot.measurement().channels()[0].maximum() == 29,
                    "throttled statistics catch up to the most recent snapshot after 500ms");
            plot.refreshMeasurementsIfDue();
            check(statsUpdates[0] == 2, "unchanged snapshots do not recompute statistics");
            plot.setMeasurementEnabled(true);
            check(statsUpdates[0] == 3 && plot.measurement().interval(), "manual measurement still updates immediately");
        });
        System.out.println("PASS " + checks + " plot mouse/column/pan/zoom/history checks + screenshot: " + args[0]);
    }
}
