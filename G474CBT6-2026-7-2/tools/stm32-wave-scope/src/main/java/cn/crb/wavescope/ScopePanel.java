package cn.crb.wavescope;

import cn.crb.wavescope.core.GdbResolver;
import cn.crb.wavescope.core.OpenOcdClient;
import cn.crb.wavescope.core.SampleBuffer;
import cn.crb.wavescope.core.ScalarType;
import cn.crb.wavescope.core.ScopeSession;
import cn.crb.wavescope.core.VariableWriter;
import cn.crb.wavescope.core.WaveMeasurements;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;

public final class ScopePanel extends JPanel implements Disposable {
    private final ScopeSettings.StateData settings;
    private final String basePath;
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> visibleRows = new ArrayList<>();
    private final WatchModel model = new WatchModel();
    private final JTable table = new JTable(model);
    private final WavePlot plot = new WavePlot();
    private final JLabel cursorStatus = new JLabel("光标：悬停读数，单击固定整列数据。");
    private WavePlot.CursorReading cursorReading;
    private WaveMeasurements.Result measurement;
    private final JLabel measurementStatus = new JLabel("统计范围：当前保留的采样记录");
    private final MeasurementModel measurementModel = new MeasurementModel();
    private final JCheckBox measure = new JCheckBox("双游标测量");
    private final JButton write = new JButton("写入数值");
    private final JLabel status = new JLabel("就绪：启动 CLion 的 OpenOCD 调试并运行程序，然后开始采样。");
    private final JButton start = new JButton("开始采样"), stop = new JButton("停止"), demo = new JButton("演示");
    private final JButton setup = new JButton("连接设置"), add = new JButton("添加变量"), remove = new JButton("删除");
    private final JSpinner period, window;
    private final JCheckBox freeze = new JCheckBox("冻结显示");
    private final JCheckBox fastSampling = new JCheckBox("高速采样");
    private final JComboBox<String> layout = new JComboBox<>(new String[]{"分轨 · 独立刻度", "叠加 · 共用刻度"});
    private final Timer refresh;
    private ScopeSession session;
    private List<Row> activeRows = List.of();
    private List<String> activeNames = List.of();
    private SampleBuffer.Snapshot displayed;
    private boolean busy, demoMode, writing;
    private volatile boolean disposed;
    private volatile int generation;
    private String statusOverride;
    private long displayedFrames = -1, displayedErrors = -1;

    private static final class Row {
        String expression;
        ScalarType type;
        boolean enabled;
        GdbResolver.Watch resolved;
        Row parent;
        final List<Row> children = new ArrayList<>();
        List<Integer> dimensions = List.of();
        List<String> members = List.of();
        boolean writable = true;
        boolean expanded = true;
        double latest = Double.NaN;
        Row(String expression, ScalarType type, boolean enabled) {
            this.expression = expression; this.type = type; this.enabled = enabled;
        }
        boolean isArray() { return !dimensions.isEmpty(); }
        boolean isGroup() { return isArray() || !members.isEmpty(); }
        int depth() { return parent == null ? 0 : parent.depth() + 1; }
        List<Row> leaves() { return isGroup() ? children.stream().flatMap(r -> r.leaves().stream()).toList() : List.of(this); }
        boolean contains(String name) { return expression.equals(name) || children.stream().anyMatch(r -> r.contains(name)); }
        static Row restore(ScopeSettings.SavedWatch saved, Row parent) {
            ScalarType type;
            try { type = ScalarType.valueOf(saved.type); } catch (RuntimeException ex) { type = ScalarType.AUTO; }
            Row row = new Row(saved.expression, type, saved.enabled);
            row.parent = parent; row.dimensions = List.copyOf(saved.dimensions); row.expanded = saved.expanded;
            row.members = List.copyOf(saved.members); row.writable = saved.writable;
            for (ScopeSettings.SavedWatch child : saved.children) row.children.add(restore(child, row));
            return row;
        }
        static Row describe(GdbResolver.Node node, Row parent) {
            GdbResolver.Description info = node.description();
            Row row = new Row(info.expression(), info.type(), false);
            row.parent = parent; row.dimensions = info.dimensions();
            row.members = info.members(); row.writable = info.writable();
            if (info.isGroup()) for (GdbResolver.Node child : node.children()) row.children.add(describe(child, row));
            else row.resolved = new GdbResolver.Watch(info.expression(), info.address(), info.type());
            return row;
        }
        ScopeSettings.SavedWatch save() {
            ScopeSettings.SavedWatch saved = new ScopeSettings.SavedWatch(expression);
            saved.type = type.name(); saved.enabled = enabled; saved.expanded = expanded;
            saved.dimensions = new ArrayList<>(dimensions);
            saved.members = new ArrayList<>(members); saved.writable = writable;
            saved.children = new ArrayList<>(children.stream().map(Row::save).toList());
            return saved;
        }
    }

    public ScopePanel(Project project) {
        this(project.getBasePath(), project.getService(ScopeSettings.class).getState());
    }

    /** Also used by the standalone UI smoke test. */
    public ScopePanel(String basePath, ScopeSettings.StateData settings) {
        super(new BorderLayout(5, 5));
        this.settings = settings; this.basePath = basePath == null ? "." : basePath;
        if (settings.samplingProfileVersion < 2) {
            settings.periodMs = Math.max(50, settings.periodMs);
            settings.fastSampling = false;
            settings.samplingProfileVersion = 2;
        }
        discoverDefaults();
        for (ScopeSettings.SavedWatch watch : settings.watches) {
            rows.add(Row.restore(watch, null));
        }
        rebuildRows();
        period = new JSpinner(new SpinnerNumberModel(clamp(settings.periodMs, 0, 10000), 0, 10000, 1));
        window = new JSpinner(new SpinnerNumberModel(clamp(settings.windowSeconds, 1, 600), 1, 600, 1));
        period.setPreferredSize(new Dimension(75, 28)); window.setPreferredSize(new Dimension(65, 28));
        layout.setSelectedIndex(settings.lanes ? 0 : 1);
        fastSampling.setSelected(settings.fastSampling || settings.periodMs == 0);
        fastSampling.setToolTipText("缩短读取后的等待，提高实际采样率，同时增加 SWD 占用；取消勾选恢复原来的稳健采样方式。");
        period.setToolTipText("0 = 满速（自动启用高速采样），读完一帧立即读下一帧；实际速度请看底部 Hz。");
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        for (JComponent component : List.of(setup, start, stop, demo, new JLabel("周期 ms（0=满速）"), period,
                fastSampling, new JLabel("窗口 s"), window, layout, freeze, measure)) toolbar.add(component);
        JPanel watchToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JButton clear = new JButton("清空曲线"), csv = new JButton("导出 CSV"), live = new JButton("实时跟随");
        for (JComponent component : List.of(add, remove, write, clear, csv, live)) watchToolbar.add(component);
        table.setFillsViewportHeight(true); table.setRowHeight(25);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(35);
        table.getColumnModel().getColumn(1).setPreferredWidth(280);
        table.getColumnModel().getColumn(2).setPreferredWidth(65);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(95);
        table.getColumnModel().getColumn(5).setPreferredWidth(95);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(ScalarType.values())));
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean selected, boolean focus, int row, int col) {
                Component component = super.getTableCellRendererComponent(t, v, selected, focus, row, col);
                if (!selected) {
                    int index = activeRows.indexOf(visibleRows.get(row));
                    Color color = index >= 0 ? WavePlot.COLORS[index % WavePlot.COLORS.length] : t.getForeground();
                    Color background = t.getBackground();
                    if (index >= 0 && background.getRed() + background.getGreen() + background.getBlue() > 450) color = color.darker().darker();
                    component.setForeground(color);
                }
                return component;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int index = table.rowAtPoint(e.getPoint());
                if (busy || index < 0 || table.columnAtPoint(e.getPoint()) != 1) return;
                Row row = visibleRows.get(index);
                if (row.isGroup()) { row.expanded = !row.expanded; rebuildRows(); save(); }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> setBusy(busy));
        JPanel watchPanel = new JPanel(new BorderLayout());
        JScrollPane watchScroll = new JScrollPane(table);
        watchScroll.setColumnHeaderView(table.getTableHeader());
        watchPanel.add(watchToolbar, BorderLayout.NORTH); watchPanel.add(watchScroll, BorderLayout.CENTER);
        cursorStatus.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        watchPanel.add(cursorStatus, BorderLayout.SOUTH);
        JTable measurements = new JTable(measurementModel); measurements.setRowHeight(23);
        measurements.setFillsViewportHeight(true);
        measurements.getColumnModel().getColumn(0).setPreferredWidth(240);
        JScrollPane measurementScroll = new JScrollPane(measurements);
        measurementScroll.setColumnHeaderView(measurements.getTableHeader());
        JPanel measurementPanel = new JPanel(new BorderLayout(3, 3));
        measurementPanel.add(measurementStatus, BorderLayout.NORTH); measurementPanel.add(measurementScroll, BorderLayout.CENTER);
        JSplitPane graph = new JSplitPane(JSplitPane.VERTICAL_SPLIT, plot, measurementPanel);
        graph.setResizeWeight(0.73); graph.setDividerLocation(360);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, watchPanel, graph);
        split.setResizeWeight(0.38); split.setDividerLocation(510);
        add(toolbar, BorderLayout.NORTH); add(split, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(5, 3));
        footer.setBorder(BorderFactory.createEmptyBorder(2, 7, 5, 5));
        footer.add(status, BorderLayout.NORTH);
        JLabel hint = new JLabel("OpenOCD · 20,000 帧 · 结构体/数组展开 · 拖动A/B测量 · 写入数值仅在点击时执行");
        hint.setFont(hint.getFont().deriveFont(11f)); footer.add(hint, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        start.addActionListener(e -> begin(false)); stop.addActionListener(e -> stopCapture());
        demo.addActionListener(e -> begin(true)); setup.addActionListener(e -> configure());
        add.addActionListener(e -> addWatch()); remove.addActionListener(e -> removeWatches());
        plot.setInteractionListeners(this::showCursor, () -> freeze.setSelected(true), this::returnLive);
        plot.setMeasurementListener(this::showMeasurement);
        measure.addActionListener(e -> plot.setMeasurementEnabled(measure.isSelected()));
        write.addActionListener(e -> editValue());
        live.addActionListener(e -> returnLive());
        freeze.addActionListener(e -> { if (!freeze.isSelected()) returnLive(); });
        clear.addActionListener(e -> {
            if (session != null) session.buffer.clear();
            displayed = null; measure.setSelected(false); plot.clear(); freeze.setSelected(false); updateView();
        });
        csv.addActionListener(e -> exportCsv());
        period.addChangeListener(e -> {
            if (((Number)period.getValue()).intValue() == 0) fastSampling.setSelected(true);
            save();
        });
        window.addChangeListener(e -> { save(); if (freeze.isSelected()) redraw(); });
        fastSampling.addActionListener(e -> {
            if (!fastSampling.isSelected() && ((Number)period.getValue()).intValue() == 0) period.setValue(1);
            save();
        });
        layout.addActionListener(e -> { save(); redraw(); });
        refresh = new Timer(100, e -> updateView()); refresh.setCoalesce(true); refresh.start();
        setBusy(false);
    }

    private void discoverDefaults() {
        if (settings.elf.isBlank()) {
            Path build = Path.of(basePath, "build", "Debug");
            if (Files.isDirectory(build)) try (var files = Files.list(build)) {
                List<Path> matches = files.filter(p -> p.toString().endsWith(".elf")).toList();
                if (matches.size() == 1) settings.elf = matches.getFirst().toString();
            } catch (Exception ignored) { }
        }
        if (settings.gdb.isBlank()) {
            String known = "E:/APP/CLION/cubeclt/STM32CubeCLT_1.21.0/GNU-tools-for-STM32/bin/arm-none-eabi-gdb.exe";
            if (Files.isRegularFile(Path.of(known))) settings.gdb = known;
            else {
                for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                    try {
                        Path candidate = Path.of(directory, "arm-none-eabi-gdb.exe");
                        if (Files.isRegularFile(candidate)) { settings.gdb = candidate.toString(); break; }
                    } catch (RuntimeException ignored) { }
                }
            }
        }
        if (settings.watches.isEmpty()) {
            for (String expression : List.of("adc_data.volt.V_CAP", "adc_data.power.P_BAT", "adc_data.power.P_CHASS", "loop_var.I_target")) {
                settings.watches.add(new ScopeSettings.SavedWatch(expression));
            }
        }
    }

    private void begin(boolean demoRequested) {
        if (busy || disposed || isRunning()) return;
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
        List<Row> selected = leaves().stream().filter(r -> r.enabled).toList();
        List<Row> arrayChecks = rows.stream().filter(Row::isGroup).filter(r -> r.leaves().stream().anyMatch(l -> l.enabled)).toList();
        if (selected.isEmpty()) { error("请勾选至少一个变量。"); return; }
        if (selected.size() > 32) { error("最多同时采样 32 个数值元素，请取消部分勾选。"); return; }
        if (!demoRequested && (settings.gdb.isBlank() || settings.elf.isBlank())) { configure(); return; }
        int periodMs = ((Number)period.getValue()).intValue();
        boolean fastRequested = fastSampling.isSelected();
        save();
        int token = ++generation;
        String gdbPath = settings.gdb, elfPath = settings.elf, host = settings.host;
        int port = settings.port;
        busy = true; setBusy(true);
        statusOverride = demoRequested ? "正在启动演示（模拟数据）…" : "正在从 ELF 解析地址，再连接 OpenOCD…";
        status.setText(statusOverride);
        SwingWorker<ScopeSession, Void> worker = new SwingWorker<>() {
            private List<GdbResolver.Watch> resolved;
            @Override protected ScopeSession doInBackground() throws Exception {
                if (demoRequested) {
                    resolved = new ArrayList<>();
                    for (int i = 0; i < selected.size(); i++) resolved.add(new GdbResolver.Watch(selected.get(i).expression, 0x20000000L + i * 4L, ScalarType.F32));
                    return new ScopeSession(null, resolved, null);
                }
                Path elf = Path.of(elfPath);
                long beforeTime = Files.getLastModifiedTime(elf).toMillis(), beforeSize = Files.size(elf);
                List<Row> arrays = arrayChecks;
                if (!arrays.isEmpty()) {
                    List<GdbResolver.Description> descriptions = new GdbResolver().inspect(Path.of(gdbPath), elf,
                            arrays.stream().map(r -> r.expression).toList());
                    for (int i = 0; i < arrays.size(); i++) {
                        if (!arrays.get(i).dimensions.equals(descriptions.get(i).dimensions())
                                || !arrays.get(i).members.equals(descriptions.get(i).members()) || arrays.get(i).type != descriptions.get(i).type()) {
                            throw new IllegalStateException("成员、数组长度或类型已改变，请删除后重新添加: " + arrays.get(i).expression);
                        }
                    }
                }
                List<GdbResolver.Input> inputs = selected.stream().map(r -> new GdbResolver.Input(r.expression, r.type)).toList();
                resolved = new GdbResolver().resolve(Path.of(gdbPath), elf, inputs);
                if (disposed || generation != token) throw new CancellationException();
                if (beforeTime != Files.getLastModifiedTime(elf).toMillis() || beforeSize != Files.size(elf)) {
                    throw new IllegalStateException("解析期间 ELF 改变，请重新开始。");
                }
                OpenOcdClient client = new OpenOcdClient(host, port);
                try { return new ScopeSession(client, resolved, elf); }
                catch (Exception ex) { client.close(); throw ex; }
            }
            @Override protected void done() {
                try {
                    ScopeSession candidate = get();
                    if (disposed || generation != token) { candidate.close(); return; }
                    if (session != null) session.close();
                    session = candidate; activeRows = selected; activeNames = selected.stream().map(r -> r.expression).toList();
                    demoMode = demoRequested;
                    for (int i = 0; i < selected.size(); i++) selected.get(i).resolved = demoRequested ? null : resolved.get(i);
                    displayed = null; measure.setSelected(false); plot.clear(); freeze.setSelected(false);
                    statusOverride = null;
                    candidate.start(periodMs, fastRequested);
                } catch (CancellationException ignored) { }
                catch (Exception ex) {
                    if (!disposed && generation == token) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        statusOverride = "启动失败：" + ScopeSession.message(cause);
                        status.setText(statusOverride);
                        error(ScopeSession.message(cause) + "\n\n确认 OpenOCD 已启动、Tcl 端口为 6666；不要连接 ST-LINK GDB server 的 61234 端口。");
                    }
                } finally {
                    if (!disposed && generation == token) { busy = false; setBusy(false); model.fireTableDataChanged(); }
                }
            }
        };
        worker.execute();
    }

    private void stopCapture() {
        generation++; busy = false;
        if (session != null) session.close();
        setBusy(false); updateView(); plot.refreshMeasurements();
        statusOverride = "已停止，曲线已保留。重新开始会重新解析 ELF，并清空本次历史。";
        status.setText(statusOverride);
    }

    private void updateView() {
        if (disposed || session == null) return;
        if (!freeze.isSelected() && (displayed == null || displayedFrames != session.frames || displayedErrors != session.errors)) {
            displayedFrames = session.frames; displayedErrors = session.errors;
            displayed = session.buffer.snapshot();
            for (int i = 0; i < activeRows.size(); i++) activeRows.get(i).latest = displayed.size() == 0
                    ? Double.NaN : displayed.values()[i][displayed.size() - 1];
            if (!visibleRows.isEmpty()) model.fireTableRowsUpdated(0, visibleRows.size() - 1);
            redraw();
        }
        if (!freeze.isSelected()) plot.refreshMeasurementsIfDue();
        String prefix = demoMode ? "【演示 / 模拟数据】 " : "";
        if (statusOverride == null) status.setText(prefix + String.format(Locale.ROOT, "%s · %s · %.1f Hz · 读取 %.2f ms · 帧 %d · 错误 %d%s",
                session.isClosed() ? "已停止" : "采样中", session.isMaximumSpeed() ? "满速" : session.isFastSampling() ? "高速" : "稳健",
                session.actualHz, session.readMilliseconds, session.frames, session.errors,
                session.lastError.isEmpty() ? "" : " · " + session.lastError));
        setBusy(busy);
    }

    private void redraw() {
        if (displayed != null) plot.showData(displayed, activeNames, layout.getSelectedIndex() == 0, ((Number)window.getValue()).intValue());
    }

    private void showCursor(WavePlot.CursorReading reading) {
        if (cursorReading == reading || (cursorReading != null && reading != null
                && cursorReading.seconds() == reading.seconds() && cursorReading.pinned() == reading.pinned()
                && java.util.Arrays.equals(cursorReading.values(), reading.values()))) return;
        cursorReading = reading;
        cursorStatus.setText(reading == null ? "光标：悬停读数，单击固定整列数据。" : String.format(Locale.ROOT,
                "%s t = %.6f s · 全部通道见“光标值”", reading.pinned() ? "已固定" : "光标", reading.seconds()));
        if (!visibleRows.isEmpty()) model.fireTableRowsUpdated(0, visibleRows.size() - 1);
    }

    private void returnLive() {
        freeze.setSelected(false); measure.setSelected(false); displayedFrames = displayedErrors = -1;
        plot.resumeLive(); updateView();
    }

    private void showMeasurement(WaveMeasurements.Result result) {
        measurement = result;
        measurementStatus.setText(result.interval() ? String.format(Locale.ROOT,
                "A %.6f s    B %.6f s    Δt %.3f ms · 区间统计（忽略无效点）", result.a(), result.b(), result.timeDifference() * 1000)
                : "统计范围：当前保留的采样记录 · 开启双游标可测量区间");
        if (measurementModel.getRowCount() > 0) measurementModel.fireTableRowsUpdated(0, measurementModel.getRowCount() - 1);
    }

    private List<Row> leaves() { return rows.stream().flatMap(r -> r.leaves().stream()).toList(); }

    private void rebuildRows() {
        visibleRows.clear();
        for (Row row : rows) addVisible(row);
        model.fireTableDataChanged();
    }
    private void addVisible(Row row) {
        visibleRows.add(row);
        if (row.expanded) for (Row child : row.children) addVisible(child);
    }

    private boolean isRunning() { return session != null && !session.isClosed(); }
    private void setBusy(boolean resolving) {
        boolean locked = resolving || isRunning() || writing;
        start.setEnabled(!locked); demo.setEnabled(!locked); stop.setEnabled(locked);
        setup.setEnabled(!locked); add.setEnabled(!locked); remove.setEnabled(!locked); period.setEnabled(!locked);
        fastSampling.setEnabled(!locked);
        measure.setEnabled(displayed != null && displayed.size() > 0);
        int selected = table.getSelectedRow();
        write.setEnabled(!resolving && !writing && !demoMode && selected >= 0
                && selected < visibleRows.size() && !visibleRows.get(selected).isGroup() && visibleRows.get(selected).writable);
        table.setEnabled(!resolving); // Array expansion and cursor inspection remain available during sampling.
    }

    private void configure() {
        JTextField elf = new JTextField(settings.elf, 50), gdb = new JTextField(settings.gdb, 50), host = new JTextField(settings.host, 20);
        JSpinner port = new JSpinner(new SpinnerNumberModel(clamp(settings.port, 1, 65535), 1, 65535, 1));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(4, 4, 4, 4); c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        for (Object[] item : new Object[][]{{"ELF（必须与板上固件匹配）", elf}, {"arm-none-eabi-gdb.exe", gdb}, {"OpenOCD 主机", host}, {"Tcl 端口（不是 GDB 端口）", port}}) {
            c.gridy = row++; c.gridx = 0; c.weightx = 0; form.add(new JLabel((String)item[0]), c);
            c.gridx = 1; c.weightx = 1; form.add((Component)item[1], c);
            if (item[1] == elf || item[1] == gdb) {
                JButton browse = new JButton("浏览…"); JTextField field = (JTextField)item[1];
                browse.addActionListener(e -> {
                    JFileChooser chooser = new JFileChooser(field.getText().isBlank() ? basePath : field.getText());
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) field.setText(chooser.getSelectedFile().getAbsolutePath());
                });
                c.gridx = 2; c.weightx = 0; form.add(browse, c);
            }
        }
        if (JOptionPane.showConfirmDialog(this, form, "STM32 Wave Scope 连接设置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            settings.elf = elf.getText().trim(); settings.gdb = gdb.getText().trim();
            settings.host = host.getText().trim().isEmpty() ? "127.0.0.1" : host.getText().trim();
            settings.port = ((Number)port.getValue()).intValue(); save();
        }
    }

    private void addWatch() {
        String input = JOptionPane.showInputDialog(this,
                "输入变量、结构体或数组名；会自动展开，例如 adc_data 或 loop_var\n可用逗号或空格添加多个：", "添加变量", JOptionPane.PLAIN_MESSAGE);
        if (input == null || input.isBlank()) return;
        try {
            List<String> expressions = List.of(input.trim().split("[,;\\s]+"));
            addExpressions(expressions);
        } catch (IllegalArgumentException ex) { error(ex.getMessage()); }
    }

    /** ELF inspection runs on the computer; adding an array never reads target memory. */
    void addExpressions(List<String> expressions) {
        if (busy || disposed || isRunning()) return;
        for (String expression : expressions) GdbResolver.validateExpression(expression);
        if (settings.gdb.isBlank() || settings.elf.isBlank()) { configure(); return; }
        int token = ++generation;
        String gdbPath = settings.gdb, elfPath = settings.elf;
        busy = true; setBusy(true); statusOverride = "正在从 ELF 识别变量与数组长度…"; status.setText(statusOverride);
        new SwingWorker<List<GdbResolver.Node>, Void>() {
            @Override protected List<GdbResolver.Node> doInBackground() throws Exception {
                Path elf = Path.of(elfPath);
                long time = Files.getLastModifiedTime(elf).toMillis(), size = Files.size(elf);
                List<GdbResolver.Node> result = new GdbResolver().inspectTree(Path.of(gdbPath), elf, expressions);
                if (Files.getLastModifiedTime(elf).toMillis() != time || Files.size(elf) != size) {
                    throw new IllegalStateException("解析期间 ELF 改变，请重新添加。");
                }
                return result;
            }
            @Override protected void done() {
                if (disposed || generation != token) return;
                try {
                    List<GdbResolver.Node> result = get();
                    for (GdbResolver.Node node : result) {
                        GdbResolver.Description info = node.description();
                        if (rows.stream().anyMatch(r -> r.contains(info.expression()))) continue;
                        Map<String, Boolean> existing = new LinkedHashMap<>();
                        for (Row leaf : leaves()) existing.put(leaf.expression, leaf.enabled);
                        if (info.isGroup()) rows.removeIf(r -> r.expression.startsWith(info.expression() + "[")
                                || r.expression.startsWith(info.expression() + "."));
                        Row row = Row.describe(node, null);
                        int enabled = (int)leaves().stream().filter(r -> r.enabled).count();
                        boolean selectNew = row.leaves().size() <= 32 - enabled;
                        for (Row leaf : row.leaves()) leaf.enabled = existing.getOrDefault(leaf.expression, selectNew);
                        rows.add(row);
                    }
                    rebuildRows(); save();
                    statusOverride = "已添加。点击结构体/数组名可折叠展开，勾选数值成员；最多同时采样 32 路。";
                    status.setText(statusOverride);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    statusOverride = "添加失败：" + ScopeSession.message(cause); status.setText(statusOverride);
                    error(ScopeSession.message(cause));
                } finally { busy = false; setBusy(false); }
            }
        }.execute();
    }

    private void editValue() {
        int index = table.getSelectedRow();
        if (!write.isEnabled() || index < 0) return;
        Row row = visibleRows.get(index);
        JTextField value = new JTextField(Double.isFinite(row.latest) ? WavePlot.number(row.latest) : "", 24);
        JPanel form = new JPanel(new BorderLayout(5, 7));
        form.add(new JLabel("变量：" + row.expression + "  （写入运行中的 RAM）"), BorderLayout.NORTH);
        form.add(value, BorderLayout.CENTER);
        form.add(new JLabel("输入新值，点击“写入”；固件可能在下一控制周期覆盖它。"), BorderLayout.SOUTH);
        int choice = JOptionPane.showOptionDialog(this, form, "写入变量", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, new String[]{"写入", "取消"}, "取消");
        if (choice != 0) return;
        writeValue(row.expression, value.getText().trim());
    }

    void writeValue(String expression, String value) {
        if (writing || busy || demoMode || disposed) return;
        String gdb = settings.gdb, elf = settings.elf, host = settings.host; int port = settings.port;
        writing = true; setBusy(busy);
        statusOverride = "正在核对类型并写入 " + expression + "…"; status.setText(statusOverride);
        new SwingWorker<VariableWriter.Result, Void>() {
            @Override protected VariableWriter.Result doInBackground() throws Exception {
                return new VariableWriter().write(Path.of(gdb), Path.of(elf), host, port, expression, value);
            }
            @Override protected void done() {
                try {
                    VariableWriter.Result result = get();
                    if (disposed) return;
                    statusOverride = expression + "：" + (result.matches() ? "写入并读回一致 " : "已写入，但读回值已不同 ")
                            + WavePlot.number(result.type().decode(result.observed(), 0));
                    for (Row row : leaves()) if (row.expression.equals(expression)) row.latest = result.type().decode(result.observed(), 0);
                    if (!visibleRows.isEmpty()) model.fireTableRowsUpdated(0, visibleRows.size() - 1);
                    status.setText(statusOverride);
                } catch (Exception ex) {
                    if (!disposed) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        statusOverride = "写入失败：" + ScopeSession.message(cause); status.setText(statusOverride);
                        error(statusOverride);
                    }
                } finally { writing = false; if (!disposed) setBusy(busy); }
            }
        }.execute();
    }

    private void removeWatches() {
        int[] selected = table.getSelectedRows();
        List<Row> remove = java.util.Arrays.stream(selected).mapToObj(visibleRows::get).toList();
        for (Row row : remove) { if (row.parent == null) rows.remove(row); else row.parent.children.remove(row); }
        rebuildRows(); save();
    }

    private void exportCsv() {
        if (session == null) { error("还没有采样记录。"); return; }
        JFileChooser chooser = new JFileChooser(basePath);
        chooser.setSelectedFile(new java.io.File("stm32-wave.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path output = chooser.getSelectedFile().toPath();
        if (Files.exists(output) && JOptionPane.showConfirmDialog(this, "文件已存在，覆盖？", "导出 CSV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        SampleBuffer.Snapshot data = freeze.isSelected() && displayed != null ? displayed : session.buffer.snapshot();
        List<String> labels = activeNames;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) { SampleBuffer.writeCsv(writer, labels, data); }
                return null;
            }
            @Override protected void done() {
                if (disposed) return;
                try { get(); JOptionPane.showMessageDialog(ScopePanel.this, "已导出 " + data.size() + " 帧：\n" + output); }
                catch (Exception ex) { error(ScopeSession.message(ex.getCause() == null ? ex : ex.getCause())); }
            }
        }.execute();
    }

    private void save() {
        settings.periodMs = ((Number)period.getValue()).intValue(); settings.windowSeconds = ((Number)window.getValue()).intValue();
        settings.fastSampling = fastSampling.isSelected();
        settings.lanes = layout.getSelectedIndex() == 0;
        settings.watches = new ArrayList<>(rows.stream().map(Row::save).toList());
    }
    private void error(String message) { JOptionPane.showMessageDialog(this, message, "STM32 Wave Scope", JOptionPane.ERROR_MESSAGE); }
    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
    @Override public void dispose() {
        if (disposed) return;
        disposed = true; generation++; refresh.stop();
        if (session != null) session.close();
        save();
    }

    private final class WatchModel extends AbstractTableModel {
        private final String[] columns = {"采样", "变量表达式", "类型", "地址", "当前值", "光标值"};
        @Override public int getRowCount() { return visibleRows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : column == 2 ? ScalarType.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) {
            Row item = visibleRows.get(row);
            return !busy && !writing && !isRunning() && (column == 0 || (column == 2 && !item.isGroup())
                    || (column == 1 && !item.isGroup() && item.parent == null));
        }
        @Override public Object getValueAt(int index, int column) {
            Row row = visibleRows.get(index);
            return switch (column) {
                case 0 -> row.isGroup() ? !row.leaves().isEmpty() && row.leaves().stream().allMatch(r -> r.enabled) : row.enabled;
                case 1 -> "  ".repeat(row.depth()) + (row.isGroup() ? (row.expanded ? "▼ " : "▶ ") : "") + row.expression;
                case 2 -> row.isArray() ? row.dimensions.stream().map(n -> "[" + n + "]").collect(java.util.stream.Collectors.joining())
                        : row.isGroup() ? "struct" : row.resolved == null || row.type != ScalarType.AUTO ? row.type : row.resolved.type();
                case 3 -> row.resolved == null ? "—" : String.format("0x%08X", row.resolved.address());
                case 4 -> row.isGroup() ? "" : WavePlot.number(row.latest);
                default -> {
                    int active = activeRows.indexOf(row);
                    yield active < 0 || cursorReading == null || active >= cursorReading.values().length
                            ? "—" : WavePlot.number(cursorReading.values()[active]);
                }
            };
        }
        @Override public void setValueAt(Object value, int index, int column) {
            if (!isCellEditable(index, column)) return;
            Row row = visibleRows.get(index);
            if (column == 0) {
                boolean enabled = Boolean.TRUE.equals(value);
                List<Row> changed = row.leaves();
                long others = leaves().stream().filter(r -> r.enabled && !changed.contains(r)).count();
                if (enabled && others + changed.size() > 32) { error("最多同时采样 32 个数值元素，请逐项选择。"); return; }
                for (Row leaf : changed) leaf.enabled = enabled;
            } else if (column == 1) {
                String expression = String.valueOf(value).trim();
                try {
                    GdbResolver.validateExpression(expression);
                    if (!expression.equals(row.expression) && rows.stream().anyMatch(r -> r.contains(expression))) {
                        throw new IllegalArgumentException("变量已存在: " + expression);
                    }
                    row.expression = expression; row.resolved = null;
                } catch (IllegalArgumentException ex) { error(ex.getMessage()); }
            } else if (column == 2) { row.type = (ScalarType)value; row.resolved = null; }
            if (!visibleRows.isEmpty()) fireTableRowsUpdated(0, visibleRows.size() - 1);
            save();
        }
    }

    private final class MeasurementModel extends AbstractTableModel {
        private final String[] columns = {"变量", "A值", "B值", "B−A", "最小值", "最大值", "峰峰值"};
        @Override public int getRowCount() { return activeNames.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            if (column == 0) return activeNames.get(row);
            if (measurement == null || row >= measurement.channels().length) return "—";
            WaveMeasurements.Channel stats = measurement.channels()[row];
            return WavePlot.number(switch (column) {
                case 1 -> stats.a(); case 2 -> stats.b(); case 3 -> stats.delta();
                case 4 -> stats.minimum(); case 5 -> stats.maximum(); default -> stats.peakToPeak();
            });
        }
    }
}
