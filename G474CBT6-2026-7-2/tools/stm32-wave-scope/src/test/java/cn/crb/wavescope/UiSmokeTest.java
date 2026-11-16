package cn.crb.wavescope;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import com.intellij.util.xmlb.XmlSerializer;

public final class UiSmokeTest {
    private static JButton findButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && button.getText().equals(text)) return button;
            if (component instanceof Container container) {
                JButton found = findButton(container, text); if (found != null) return found;
            }
        }
        return null;
    }
    private static JCheckBox findCheckBox(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JCheckBox box && box.getText().equals(text)) return box;
            if (component instanceof Container container) {
                JCheckBox found = findCheckBox(container, text); if (found != null) return found;
            }
        }
        return null;
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container container) { T found = find(container, type); if (found != null) return found; }
        }
        return null;
    }
    private static int row(JTable table, String expression) {
        for (int i = 0; i < table.getRowCount(); i++) if (table.getValueAt(i, 1).toString().contains(expression)) return i;
        throw new AssertionError("Missing variable: " + expression);
    }
    private static void toggleArray(JTable table, String expression) {
        Rectangle cell = table.getCellRect(row(table, expression), 1, true);
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, cell.x + 5, cell.y + 5, 1, false, MouseEvent.BUTTON1));
    }
    private static long frames(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label) {
                var matcher = Pattern.compile("帧 ([0-9]+)").matcher(label.getText());
                if (matcher.find()) return Long.parseLong(matcher.group(1));
            }
            if (component instanceof Container container) { long n = frames(container); if (n >= 0) return n; }
        }
        return -1;
    }
    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container container) layout(container);
    }
    private static JSpinner findPeriod(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JSpinner spinner && spinner.getModel() instanceof SpinnerNumberModel model
                    && model.getMinimum().equals(0)) return spinner;
            if (component instanceof Container container) {
                JSpinner found = findPeriod(container); if (found != null) return found;
            }
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        AtomicReference<ScopePanel> reference = new AtomicReference<>();
        AtomicReference<ScopeSettings.StateData> saved = new AtomicReference<>();
        String[] pinnedValue = new String[1]; long[] framesBefore = new long[1];
        SwingUtilities.invokeAndWait(() -> {
            ScopeSettings.StateData settings = new ScopeSettings.StateData(); settings.windowSeconds = 3;
            settings.gdb = args[1]; settings.elf = args[2]; saved.set(settings);
            settings.periodMs = 1; settings.fastSampling = true; settings.samplingProfileVersion = 1;
            ScopePanel panel = new ScopePanel(".", settings);
            if (settings.periodMs != 50 || settings.fastSampling || settings.samplingProfileVersion != 2) {
                throw new AssertionError("Upgrade did not slow legacy fast settings to 50ms stable sampling");
            }
            JCheckBox fast = findCheckBox(panel, "高速采样");
            if (fast == null || fast.isSelected()) throw new AssertionError("Upgrade kept the high-speed mode enabled");
            fast.doClick();
            if (!settings.fastSampling) throw new AssertionError("User cannot re-enable fast sampling after migration");
            fast.doClick();
            if (settings.fastSampling) throw new AssertionError("Fast mode disable not saved");
            fast.doClick();
            if (!settings.fastSampling) throw new AssertionError("Fast mode enable not saved");
            JSpinner period = findPeriod(panel);
            if (period == null) throw new AssertionError("Missing maximum speed period option");
            period.setValue(0);
            if (settings.periodMs != 0 || !settings.fastSampling) throw new AssertionError("Maximum speed setting not saved");
            fast.doClick();
            if (settings.periodMs != 1 || settings.fastSampling) throw new AssertionError("Leaving maximum speed did not restore a valid period");
            period.setValue(0);
            if (!fast.isSelected()) throw new AssertionError("Maximum speed did not enable fast mode");
            fast.doClick(); period.setValue(50); // Exercise the slower mode requested by the user.
            panel.setSize(1450, 620); layout(panel); reference.set(panel);
            panel.addExpressions(List.of("adc_data.adc_data_raw", "adc_data.adc_data_mid"));
        });
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        boolean[] ready = {false};
        while (!ready[0] && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> ready[0] = findButton(reference.get(), "演示").isEnabled());
            Thread.sleep(20);
        }
        if (!ready[0]) throw new AssertionError("ELF array inspection did not complete");
        SwingUtilities.invokeAndWait(() -> {
            ScopePanel panel = reference.get(); JTable table = find(panel, JTable.class);
            if (table.getRowCount() != 12) throw new AssertionError("Arrays were not expanded into six elements");
            toggleArray(table, "adc_data.adc_data_raw");
            if (table.getRowCount() != 10) throw new AssertionError("Collapse did not hide only the array children");
            toggleArray(table, "adc_data.adc_data_raw");
            if (table.getRowCount() != 12) throw new AssertionError("Expand did not restore the array children");
            table.getModel().setValueAt(false, row(table, "adc_data.adc_data_mid"), 0);
            if (Boolean.TRUE.equals(table.getValueAt(row(table, "adc_data.adc_data_mid[0]"), 0))) {
                throw new AssertionError("Array checkbox did not apply to its elements");
            }
            toggleArray(table, "adc_data.adc_data_raw"); // Selected elements must still be sampled while collapsed.
            JButton demo = findButton(panel, "演示");
            if (demo == null) throw new AssertionError("Missing demo control"); demo.doClick();
        });
        Thread.sleep(2400);
        SwingUtilities.invokeAndWait(() -> {
            ScopePanel panel = reference.get(); layout(panel);
            if (!findButton(panel, "停止").isEnabled()) throw new AssertionError("Demo did not start");
            if (findCheckBox(panel, "高速采样").isEnabled()) throw new AssertionError("Sampling mode changed during capture");
            JTable table = find(panel, JTable.class);
            toggleArray(table, "adc_data.adc_data_raw");
            int child = row(table, "adc_data.adc_data_raw[1]");
            if (table.getValueAt(child, 4).equals("—")) throw new AssertionError("Collapsed selected array child was not sampled");
            if (table.isCellEditable(child, 0)) throw new AssertionError("Sample membership editable while running");
            WavePlot plot = find(panel, WavePlot.class);
            int x = 85 + (plot.getWidth() - 105) / 3;
            plot.dispatchEvent(new MouseEvent(plot, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, 100, 0, false));
            plot.dispatchEvent(new MouseEvent(plot, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, x, 100, 1, false, MouseEvent.BUTTON1));
            plot.dispatchEvent(new MouseEvent(plot, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x, 100, 1, false, MouseEvent.BUTTON1));
            if (!findCheckBox(panel, "冻结显示").isSelected() || plot.cursorReading() == null || !plot.cursorReading().pinned()) {
                throw new AssertionError("Click did not freeze the view and pin the cursor");
            }
            pinnedValue[0] = table.getValueAt(child, 5).toString();
            if (!pinnedValue[0].equals(WavePlot.number(plot.cursorReading().values()[5]))) throw new AssertionError("Cursor table used the wrong channel");
            framesBefore[0] = frames(panel);
        });
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> {
            ScopePanel panel = reference.get(); JTable table = find(panel, JTable.class);
            if (frames(panel) <= framesBefore[0]) throw new AssertionError("Inspecting history stopped background sampling");
            if (!table.getValueAt(row(table, "adc_data.adc_data_raw[1]"), 5).equals(pinnedValue[0])) throw new AssertionError("Pinned table values drifted");
            findCheckBox(panel, "双游标测量").doClick();
            if (!find(panel, WavePlot.class).measurement().interval() || !findCheckBox(panel, "冻结显示").isSelected()) {
                throw new AssertionError("Measurement mode did not create both cursors on frozen history");
            }
            table.setRowSelectionInterval(0, 0);
            if (findButton(panel, "写入数值").isEnabled()) throw new AssertionError("Demo mode enabled live target writes");
            BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
            try { ImageIO.write(image, "png", Path.of(args[0]).toFile()); } catch (Exception ex) { throw new RuntimeException(ex); }
            findButton(panel, "实时跟随").doClick();
            if (findCheckBox(panel, "冻结显示").isSelected() || find(panel, WavePlot.class).cursorReading() != null) {
                throw new AssertionError("Live button did not release history and cursor");
            }
            findButton(panel, "停止").doClick();
            if (!findCheckBox(panel, "高速采样").isEnabled()) throw new AssertionError("Sampling mode remained locked");
            panel.dispose();
        });
        SwingUtilities.invokeAndWait(() -> {
            ScopeSettings.StateData settings = XmlSerializer.deserialize(XmlSerializer.serialize(saved.get()), ScopeSettings.StateData.class);
            ScopePanel panel = new ScopePanel(".", settings);
            if (((Number)findPeriod(panel).getValue()).intValue() != 50 || findCheckBox(panel, "高速采样").isSelected()) {
                throw new AssertionError("Slower sampling settings were lost when reopening the panel");
            }
            JTable table = find(panel, JTable.class);
            if (table.getRowCount() != 12 || !Boolean.TRUE.equals(table.getValueAt(row(table, "adc_data.adc_data_raw[1]"), 0))
                    || Boolean.TRUE.equals(table.getValueAt(row(table, "adc_data.adc_data_mid[1]"), 0))) {
                throw new AssertionError("Array shape, expansion or element selection lost during XML persistence");
            }
            panel.dispose();
        });
        SwingUtilities.invokeAndWait(() -> {
            ScopeSettings.StateData settings = new ScopeSettings.StateData();
            settings.periodMs = 200; settings.fastSampling = true; settings.samplingProfileVersion = 1;
            ScopePanel panel = new ScopePanel(".", settings);
            if (settings.periodMs != 200 || settings.fastSampling) throw new AssertionError("Migration sped up an already slower period");
            panel.dispose();
            settings.periodMs = 0; settings.fastSampling = true;
            panel = new ScopePanel(".", settings);
            if (((Number)findPeriod(panel).getValue()).intValue() != 0 || !findCheckBox(panel, "高速采样").isSelected()) {
                throw new AssertionError("Migration repeatedly overrides a later user choice");
            }
            panel.dispose();
        });
        SwingUtilities.invokeAndWait(() -> {
            ScopeSettings.StateData settings = new ScopeSettings.StateData();
            settings.gdb = args[1]; settings.elf = args[2]; settings.samplingProfileVersion = 1;
            ScopePanel panel = new ScopePanel(".", settings); panel.setSize(1450, 620); layout(panel); reference.set(panel);
            panel.addExpressions(List.of("adc_data", "loop_var"));
        });
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25); ready[0] = false;
        while (!ready[0] && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> ready[0] = findButton(reference.get(), "演示").isEnabled()); Thread.sleep(20);
        }
        if (!ready[0]) throw new AssertionError("Nested struct inspection did not complete");
        SwingUtilities.invokeAndWait(() -> {
            ScopePanel panel = reference.get(); JTable table = find(panel, JTable.class);
            int count = table.getRowCount();
            row(table, "adc_data.volt.V_CAP"); row(table, "adc_data.power.P_CHASS"); row(table, "loop_var.loop_protect.protect_flag");
            toggleArray(table, "adc_data.volt");
            if (table.getRowCount() != count - 2) throw new AssertionError("Nested struct collapse affected the wrong rows");
            toggleArray(table, "adc_data.volt");
            table.setRowSelectionInterval(row(table, "adc_data"), row(table, "adc_data"));
            if (findButton(panel, "写入数值").isEnabled()) throw new AssertionError("Aggregate row enabled a whole-struct write");
            table.setRowSelectionInterval(row(table, "loop_var.V_target"), row(table, "loop_var.V_target"));
            if (!findButton(panel, "写入数值").isEnabled()) throw new AssertionError("Scalar struct member cannot invoke explicit write");
            panel.dispose();
        });
        System.out.println("PASS Swing ELF array expansion/selection/XML persistence + pinned columns/live acquisition + screenshot: " + args[0]);
        System.exit(0);
    }
}
