package cn.crb.wavescope;

import cn.crb.wavescope.core.SampleBuffer;
import cn.crb.wavescope.core.WaveMeasurements;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class WavePlot extends JPanel {
    public static final Color[] COLORS = {new Color(65, 207, 205), new Color(255, 192, 79),
            new Color(121, 169, 255), new Color(245, 125, 155), new Color(165, 214, 105), new Color(200, 153, 255)};
    public record CursorReading(double seconds, double[] values, boolean pinned) { }
    private SampleBuffer.Snapshot snapshot = new SampleBuffer.Snapshot(new double[0], new double[0][]);
    private List<String> names = List.of();
    private boolean lanes = true, dragging;
    private int seconds = 15, mouseX = -1, mouseY = -1, pressX = -1;
    private double viewStart = Double.NaN, span = 15, pressStart;
    private CursorReading pinned, reading;
    private Consumer<CursorReading> cursorListener = r -> { };
    private Runnable pauseListener = () -> { }, liveListener = () -> { };
    private Consumer<WaveMeasurements.Result> measurementListener = r -> { };
    private boolean measuring;
    private double timeA = Double.NaN, timeB = Double.NaN;
    private int dragLine = -1;
    private WaveMeasurements.Result measurement;
    private SampleBuffer.Snapshot measuredSnapshot;
    private long nextMeasurementNanos;
    private static final int LEFT = 85, RIGHT = 20, TOP = 28, BOTTOM = 26;

    public WavePlot() {
        setPreferredSize(new Dimension(900, 340));
        setMinimumSize(new Dimension(250, 150));
        setBackground(new Color(25, 29, 36));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                mouseX = e.getX(); mouseY = e.getY(); updateCursor();
            }
            @Override public void mouseExited(MouseEvent e) {
                mouseX = mouseY = -1; updateCursor();
            }
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || !inPlot(e.getX(), e.getY()) || snapshot.size() == 0) return;
                pressX = e.getX(); pressStart = visibleStart(); dragging = false;
                if (measuring) {
                    double ax = LEFT + (timeA - visibleStart()) * plotWidth() / span;
                    double bx = LEFT + (timeB - visibleStart()) * plotWidth() / span;
                    if (Math.min(Math.abs(e.getX() - ax), Math.abs(e.getX() - bx)) <= 9) {
                        dragLine = Math.abs(e.getX() - ax) <= Math.abs(e.getX() - bx) ? 0 : 1;
                    }
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressX < 0 || snapshot.size() == 0) return;
                if (dragLine >= 0) {
                    int index = nearestAt((int)clamp(e.getX(), LEFT, getWidth() - RIGHT));
                    if (index >= 0) {
                        if (dragLine == 0) timeA = snapshot.times()[index]; else timeB = snapshot.times()[index];
                        dragging = true; updateMeasurement(); repaint();
                    }
                    return;
                }
                if (!dragging && Math.abs(e.getX() - pressX) < 4) return;
                if (!dragging) { pauseListener.run(); dragging = true; setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)); }
                double earliest = snapshot.times()[0], latest = snapshot.times()[snapshot.size() - 1];
                double maxStart = Math.max(earliest, latest - span);
                viewStart = clamp(pressStart - (e.getX() - pressX) * span / plotWidth(), earliest, maxStart);
                mouseX = e.getX(); mouseY = e.getY(); updateCursor();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (pressX < 0) return;
                if (dragLine < 0 && !dragging && inPlot(e.getX(), e.getY())) {
                    if (e.getClickCount() >= 2) { liveListener.run(); resumeLive(); }
                    else {
                        int index = nearestAt(e.getX());
                        if (index >= 0) {
                            pauseListener.run(); viewStart = visibleStart();
                            pinned = at(index, true); mouseX = e.getX(); mouseY = e.getY(); updateCursor();
                        }
                    }
                }
                pressX = -1; dragging = false; dragLine = -1; setCursor(Cursor.getDefaultCursor());
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (!inPlot(e.getX(), e.getY()) || snapshot.size() == 0) return;
                pauseListener.run();
                double fraction = (e.getX() - LEFT) / (double)plotWidth();
                double anchor = visibleStart() + fraction * span;
                span = clamp(span * Math.pow(1.25, e.getPreciseWheelRotation()), 0.01, 600);
                double earliest = snapshot.times()[0], latest = snapshot.times()[snapshot.size() - 1];
                viewStart = clamp(anchor - fraction * span, earliest, Math.max(earliest, latest - span));
                mouseX = e.getX(); mouseY = e.getY(); updateCursor(); e.consume();
            }
        };
        addMouseMotionListener(mouse); addMouseListener(mouse); addMouseWheelListener(mouse);
    }

    public void setInteractionListeners(Consumer<CursorReading> cursor, Runnable pause, Runnable live) {
        cursorListener = cursor; pauseListener = pause; liveListener = live;
    }
    public CursorReading cursorReading() { return reading; }
    public void setMeasurementListener(Consumer<WaveMeasurements.Result> listener) { measurementListener = listener; }
    public WaveMeasurements.Result measurement() { return measurement; }
    public void setMeasurementEnabled(boolean enabled) {
        measuring = enabled;
        if (enabled && snapshot.size() > 0) {
            pauseListener.run(); viewStart = visibleStart(); pinned = reading = null; cursorListener.accept(null);
            timeA = snapshot.times()[WaveMeasurements.nearest(snapshot.times(), visibleStart() + span / 3)];
            timeB = snapshot.times()[WaveMeasurements.nearest(snapshot.times(), visibleStart() + span * 2 / 3)];
        }
        updateMeasurement(); repaint();
    }
    private void updateMeasurement() {
        measurement = WaveMeasurements.calculate(snapshot, measuring, timeA, timeB);
        measuredSnapshot = snapshot;
        nextMeasurementNanos = System.nanoTime() + 500_000_000L;
        measurementListener.accept(measurement);
    }
    public void refreshMeasurements() { updateMeasurement(); }
    public void refreshMeasurementsIfDue() {
        if (measurement == null || (snapshot != measuredSnapshot && System.nanoTime() >= nextMeasurementNanos)) updateMeasurement();
    }
    public double visibleStart() {
        if (Double.isFinite(viewStart)) return viewStart;
        return snapshot.size() == 0 ? 0 : Math.max(snapshot.times()[0], snapshot.times()[snapshot.size() - 1] - span);
    }
    public double visibleSpan() { return span; }
    public void resumeLive() {
        viewStart = Double.NaN; span = seconds; pinned = reading = null; mouseX = mouseY = -1;
        measuring = false; timeA = timeB = Double.NaN; updateMeasurement();
        cursorListener.accept(null); repaint();
    }
    public void clear() {
        snapshot = new SampleBuffer.Snapshot(new double[0], new double[0][]);
        resumeLive();
    }
    public void showData(SampleBuffer.Snapshot data, List<String> labels, boolean lanes, int seconds) {
        snapshot = data; names = List.copyOf(labels); this.lanes = lanes;
        if (this.seconds != seconds) { this.seconds = seconds; span = seconds; }
        if (measuring && data.size() > 0 && (!Double.isFinite(timeA) || !Double.isFinite(timeB))) {
            timeA = data.times()[0]; timeB = data.times()[data.size() - 1];
        }
        updateCursor(); refreshMeasurementsIfDue();
    }
    private int plotWidth() { return Math.max(1, getWidth() - LEFT - RIGHT); }
    private boolean inPlot(int x, int y) {
        return x >= LEFT && x <= getWidth() - RIGHT && y >= TOP && y <= getHeight() - BOTTOM;
    }
    private int nearestAt(int x) {
        if (snapshot.size() == 0) return -1;
        double target = visibleStart() + (x - LEFT) * span / plotWidth();
        double[] times = snapshot.times();
        int index = Arrays.binarySearch(times, target);
        if (index < 0) {
            int next = -index - 1;
            if (next == times.length) index = times.length - 1;
            else if (next == 0) index = 0;
            else index = target - times[next - 1] <= times[next] - target ? next - 1 : next;
        }
        return times[index] >= visibleStart() && times[index] <= visibleStart() + span ? index : -1;
    }
    private CursorReading at(int index, boolean fixed) {
        double[] values = new double[Math.min(names.size(), snapshot.values().length)];
        for (int i = 0; i < values.length; i++) values[i] = snapshot.values()[i][index];
        return new CursorReading(snapshot.times()[index], values, fixed);
    }
    private void updateCursor() {
        int index = inPlot(mouseX, mouseY) ? nearestAt(mouseX) : -1;
        reading = pinned != null ? pinned : index >= 0 ? at(index, false) : null;
        cursorListener.accept(reading); repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            int width = plotWidth(), height = getHeight() - TOP - BOTTOM, size = snapshot.size();
            if (width < 20 || height < 20) return;
            if (size == 0 || names.isEmpty()) {
                g.setColor(new Color(155, 167, 184));
                g.drawString("等待采样 · 悬停读数 / 单击固定 / 拖拽平移 / 滚轮缩放", LEFT, TOP + 35); return;
            }
            double start = visibleStart(), end = start + span;
            int first = lowerBound(start), last = lowerBound(end);
            first = Math.max(0, first - 1); last = Math.min(size, last + 1);
            int count = Math.min(names.size(), snapshot.values().length), laneCount = lanes ? count : 1;
            double[] shared = range(-1, first, last);
            for (int lane = 0; lane < laneCount; lane++) {
                int y0 = TOP + lane * height / laneCount, h = Math.max(1, height / laneCount - 8);
                double[] bounds = lanes ? range(lane, first, last) : shared;
                double min = bounds[0], max = bounds[1];
                g.setColor(new Color(47, 55, 67));
                for (int n = 0; n <= 4; n++) { int y = y0 + h * n / 4; g.drawLine(LEFT, y, LEFT + width, y); }
                g.setColor(new Color(157, 169, 186));
                g.drawString(number(max), 5, y0 + 11); g.drawString(number(min), 5, y0 + h);
                Graphics2D curves = (Graphics2D)g.create();
                curves.clipRect(LEFT, y0, width, h + 1);
                for (int channel = 0; channel < count; channel++) {
                    if (lanes && channel != lane) continue;
                    Path2D path = new Path2D.Double(); boolean connected = false;
                    double previousTime = Double.NaN;
                    for (int n = first; n < last; n++) {
                        double value = snapshot.values()[channel][n];
                        if (!Double.isFinite(value)) { connected = false; continue; }
                        double x = LEFT + (snapshot.times()[n] - start) / span * width;
                        double y = y0 + h - (value - min) / (max - min) * h;
                        if (connected && snapshot.times()[n] - previousTime <= Math.max(0.25, span / 10.0)) path.lineTo(x, y);
                        else { path.moveTo(x, y); path.lineTo(x + 0.3, y); }
                        previousTime = snapshot.times()[n]; connected = true;
                    }
                    curves.setColor(COLORS[channel % COLORS.length]); curves.setStroke(new BasicStroke(1.5f)); curves.draw(path);
                    if (reading != null && channel < reading.values().length && Double.isFinite(reading.values()[channel])) {
                        double x = LEFT + (reading.seconds() - start) / span * width;
                        double y = y0 + h - (reading.values()[channel] - min) / (max - min) * h;
                        curves.fillOval((int)x - 3, (int)y - 3, 7, 7);
                    }
                }
                curves.dispose();
                if (lanes) {
                    String label = names.get(lane) + "   " + number(snapshot.values()[lane][size - 1]);
                    g.setColor(new Color(25, 29, 36, 225));
                    g.fillRect(LEFT + 4, y0 + 1, Math.min(width - 4, g.getFontMetrics().stringWidth(label) + 8), 16);
                    g.setColor(COLORS[lane % COLORS.length]); g.drawString(label, LEFT + 7, y0 + 13);
                }
            }
            g.setStroke(new BasicStroke(1));
            for (int n = 0; n <= 5; n++) {
                int x = LEFT + n * width / 5;
                g.setColor(new Color(47, 55, 67)); g.drawLine(x, TOP, x, TOP + height);
                g.setColor(new Color(157, 169, 186));
                g.drawString(String.format(Locale.ROOT, span < 1 ? "%.3f s" : "%.1f s", start + n * span / 5.0), x - 12, getHeight() - 6);
            }
            g.setColor(new Color(157, 169, 186));
            g.drawString((Double.isFinite(viewStart) ? "历史" : "实时") + (lanes ? " · 独立刻度" : " · 共用刻度")
                    + "    拖拽平移 · 滚轮缩放 · 双击回到实时", LEFT, 15);
            paintReading(g, start, width, height);
            if (measuring && measurement != null && Double.isFinite(measurement.a())) {
                for (int line = 0; line < 2; line++) {
                    double t = line == 0 ? timeA : timeB;
                    int x = LEFT + (int)((t - start) / span * width);
                    if (x < LEFT || x > LEFT + width) continue;
                    g.setColor(line == 0 ? new Color(255, 211, 100) : new Color(245, 125, 195));
                    g.setStroke(new BasicStroke(1.5f)); g.drawLine(x, TOP, x, TOP + height);
                    g.fillRect(x - 9, TOP, 18, 18); g.setColor(new Color(22, 25, 32));
                    g.drawString(line == 0 ? "A" : "B", x - 4, TOP + 13);
                }
            }
        } finally { g.dispose(); }
    }

    private void paintReading(Graphics2D g, double start, int width, int height) {
        if (reading == null) return;
        int x = LEFT + (int)((reading.seconds() - start) / span * width);
        if (x >= LEFT && x <= LEFT + width) {
            g.setColor(reading.pinned() ? new Color(255, 211, 100) : new Color(200, 209, 222, 180));
            g.drawLine(x, TOP, x, TOP + height);
        }
        int count = Math.min(reading.values().length, Math.min(8, Math.max(1, (height - 45) / 18)));
        String title = (reading.pinned() ? "已固定  " : "光标  ") + String.format(Locale.ROOT, "t = %.6f s", reading.seconds());
        int boxWidth = g.getFontMetrics().stringWidth(title) + 24;
        for (int i = 0; i < count; i++) boxWidth = Math.max(boxWidth,
                g.getFontMetrics().stringWidth(names.get(i) + "  " + number(reading.values()[i])) + 24);
        boxWidth = Math.min(width - 12, boxWidth);
        int boxHeight = 28 + 18 * count + (count < reading.values().length ? 18 : 0);
        int bx = Math.min(LEFT + width - boxWidth - 4, Math.max(LEFT + 4, mouseX + 16));
        int by = Math.max(TOP + 4, Math.min(TOP + height - boxHeight - 4, mouseY + 16));
        g.setColor(new Color(16, 20, 27, 238)); g.fillRoundRect(bx, by, boxWidth, boxHeight, 9, 9);
        g.setColor(new Color(115, 129, 148)); g.drawRoundRect(bx, by, boxWidth, boxHeight, 9, 9);
        Graphics2D text = (Graphics2D)g.create(); text.clipRect(bx + 8, by + 2, boxWidth - 16, boxHeight - 4);
        text.setColor(new Color(233, 238, 245)); text.drawString(title, bx + 10, by + 18);
        for (int i = 0; i < count; i++) {
            text.setColor(COLORS[i % COLORS.length]);
            text.drawString(names.get(i) + "  " + number(reading.values()[i]), bx + 10, by + 38 + i * 18);
        }
        if (count < reading.values().length) {
            text.setColor(new Color(180, 191, 207)); text.drawString("其余通道见左侧“光标值”", bx + 10, by + 38 + count * 18);
        }
        text.dispose();
    }
    private int lowerBound(double value) {
        int low = 0, high = snapshot.size();
        while (low < high) { int mid = (low + high) >>> 1; if (snapshot.times()[mid] < value) low = mid + 1; else high = mid; }
        return low;
    }
    private double[] range(int selected, int first, int last) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int channel = 0; channel < snapshot.values().length; channel++) {
            if (selected >= 0 && selected != channel) continue;
            for (int n = first; n < last; n++) {
                double value = snapshot.values()[channel][n];
                if (Double.isFinite(value)) { min = Math.min(min, value); max = Math.max(max, value); }
            }
        }
        if (!Double.isFinite(min)) return new double[]{-1, 1};
        double pad = Math.max((max - min) * 0.08, Math.max(Math.abs(max) * 0.001, 0.001));
        return new double[]{min - pad, max + pad};
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    public static String number(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6g", value) : "—"; }
}
