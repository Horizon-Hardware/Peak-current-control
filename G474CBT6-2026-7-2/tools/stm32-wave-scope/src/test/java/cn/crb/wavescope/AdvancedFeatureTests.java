package cn.crb.wavescope;

import cn.crb.wavescope.core.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class AdvancedFeatureTests {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String label) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    private static GdbResolver.Node child(GdbResolver.Node root, String suffix) {
        return root.children().stream().filter(n -> n.description().expression().endsWith(suffix)).findFirst().orElseThrow();
    }
    public static void main(String[] args) throws Exception {
        check(ScalarType.F32.decode(ScalarType.F32.encode("28.25"), 0) == 28.25, "float write encoding");
        check(ScalarType.I8.decode(ScalarType.I8.encode("-128"), 0) == -128, "signed byte encoding");
        check(ScalarType.U32.decode(ScalarType.U32.encode("0xffffffff"), 0) == 4294967295.0, "unsigned 32-bit exact bound");
        check(ScalarType.BOOL.decode(ScalarType.BOOL.encode("true"), 0) == 1, "boolean encoding");
        rejects(() -> ScalarType.U8.encode("256"), "byte overflow rejected");
        rejects(() -> ScalarType.U32.encode("-1"), "unsigned negative rejected");
        rejects(() -> ScalarType.I32.encode("2147483648"), "signed overflow rejected");
        rejects(() -> ScalarType.I32.encode("1.5"), "fraction rejected for integer");
        rejects(() -> ScalarType.I32.encode("--1"), "double sign rejected");
        rejects(() -> ScalarType.F32.encode("NaN"), "NaN write rejected");
        rejects(() -> ScalarType.F32.encode("1e100"), "float overflow rejected");
        rejects(() -> ScalarType.F32.encode("1; halt"), "executable input rejected");
        double[] times = {0, 1, 2, 3, 4};
        SampleBuffer.Snapshot data = new SampleBuffer.Snapshot(times, new double[][]{{1, 4, Double.NaN, -2, 10}, {0, 1, 2, 3, 4}});
        WaveMeasurements.Result all = WaveMeasurements.calculate(data, false, 0, 0);
        check(all.channels()[0].minimum() == -2 && all.channels()[0].maximum() == 10 && all.channels()[0].peakToPeak() == 12,
                "retained-history min/max/peak-to-peak");
        WaveMeasurements.Result interval = WaveMeasurements.calculate(data, true, 3, 1);
        check(interval.timeDifference() == 2 && interval.channels()[0].minimum() == -2 && interval.channels()[0].maximum() == 4
                && interval.channels()[0].peakToPeak() == 6, "reversed cursors give inclusive interval extrema");
        check(interval.channels()[0].delta() == 6 && interval.channels()[0].samples() == 2 && interval.channels()[0].missing() == 1,
                "endpoint B-A differs from peak-to-peak and gaps are counted");
        check(Double.isNaN(WaveMeasurements.calculate(data, true, 2, 2).channels()[0].minimum()), "all-gap interval remains invalid");
        Path gdb = Path.of(args[0]), elf = Path.of(args[1]);
        GdbResolver resolver = new GdbResolver();
        GdbResolver.Node tree = resolver.inspectTree(gdb, elf, List.of("fixture")).getFirst();
        check(tree.description().isStruct() && tree.children().size() == 11, "real ELF named/inline struct fields; pointers and bitfields excluded");
        check(child(tree, ".records").children().get(1).children().get(1).description().expression().equals("fixture.records[1].value"),
                "array of structs expands through scalar members");
        check(child(tree, ".matrix").children().get(1).children().size() == 3, "struct matrix array dimensions");
        check(child(tree, ".inline_record").children().getFirst().description().expression().endsWith(".inner"), "inline nested struct");
        check(!child(tree, ".fixed").description().writable() && !child(tree, ".readonly_record").children().getFirst().description().writable(),
                "const fields and const parent preserve read-only metadata");
        List<GdbResolver.Node> real = resolver.inspectTree(gdb, Path.of(args[2]), List.of("adc_data", "loop_var"));
        check(real.getFirst().description().members().size() == 5 && child(real.getFirst(), ".volt").children().size() == 2,
                "real firmware ADC struct expands arrays and nested voltage fields");
        check(child(real.get(1), ".loop_protect").children().size() == 2, "real firmware loop protection nested struct");
        try (MemoryServer server = new MemoryServer()) {
            VariableWriter.Result result = new VariableWriter().write(gdb, elf, "127.0.0.1", server.port(), "fixture.editable", "28.25");
            check(result.matches() && result.type().decode(result.observed(), 0) == 28.25 && server.writes == 1,
                    "explicit variable writer resolves the ELF, writes exactly once and verifies readback");
            new VariableWriter().write(gdb, elf, "127.0.0.1", server.port(), "fixture.small", "255");
            new VariableWriter().write(gdb, elf, "127.0.0.1", server.port(), "fixture.half", "0xabcd");
            new VariableWriter().write(gdb, elf, "127.0.0.1", server.port(), "fixture.signed_value", "-123");
            check(server.widths.equals(List.of(32, 8, 16, 32)), "writes use the scalar width without modifying neighbor bytes");
            int before = server.writes;
            rejects(() -> new VariableWriter().write(gdb, elf, "127.0.0.1", server.port(), "fixture.fixed", "1"), "const write blocked");
            rejects(() -> new VariableWriter().write(gdb, elf, "127.0.0.1", server.port(), "fixture.readonly_record.count", "1"), "const nested member write blocked");
            rejects(() -> new VariableWriter().write(gdb, elf, "127.0.0.1", server.port(), "fixture", "1"), "aggregate write blocked");
            rejects(() -> new VariableWriter().write(gdb, elf, "127.0.0.1", server.port(), "fixture.wide", "1"), "64-bit live write blocked");
            check(server.writes == before, "rejected writes never reach the target transport");
            try (OpenOcdClient client = new OpenOcdClient("127.0.0.1", server.port())) {
                rejects(() -> client.writeScalar(0x40000000L, ScalarType.U32, "1"), "peripheral write blocked");
                rejects(() -> client.writeScalar(0x20000001L, ScalarType.U16, "1"), "unaligned write blocked");
                server.fail = true;
                rejects(() -> client.writeScalar(0x20000000L, ScalarType.U32, "1"), "failed write acknowledged as failure");
                check(server.writes == before + 1, "failed write is not retried");
            }
        }
        System.out.println("PASS " + checks + " struct/encoding/write/readback/statistics checks; simulated RAM only");
    }

    private static final class MemoryServer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        private final byte[] memory = new byte[4096];
        final List<Integer> widths = new ArrayList<>();
        volatile int writes; volatile boolean fail;
        private final Thread worker;
        MemoryServer() throws IOException {
            worker = new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    socket.setTcpNoDelay(true); InputStream input = socket.getInputStream();
                    ByteArrayOutputStream request = new ByteArrayOutputStream(); int b;
                    while ((b = input.read()) >= 0) {
                        if (b != 0x1a) { request.write(b); continue; }
                        String command = request.toString(StandardCharsets.UTF_8); request.reset();
                        var write = java.util.regex.Pattern.compile("\\[target current\\] write_memory 0x([0-9a-f]+) (8|16|32) \\{0x([0-9a-f]+)\\}; format \\{WAVE_WRITE_OK\\}").matcher(command);
                        var read = java.util.regex.Pattern.compile("\\[target current\\] read_memory 0x([0-9a-f]+) 32 ([0-9]+)").matcher(command);
                        String reply;
                        if (write.matches()) {
                            writes++; int width = Integer.parseInt(write.group(2)); widths.add(width);
                            int offset = (int)(Long.parseLong(write.group(1), 16) - 0x20000000L);
                            long bits = Long.parseLong(write.group(3), 16);
                            if (!fail) for (int i = 0; i < width / 8; i++) memory[offset + i] = (byte)(bits >>> (i * 8));
                            reply = fail ? "Error: simulated write failed" : "WAVE_WRITE_OK";
                        } else if (read.matches()) {
                            int offset = (int)(Long.parseLong(read.group(1), 16) - 0x20000000L), count = Integer.parseInt(read.group(2));
                            StringJoiner words = new StringJoiner(" ");
                            for (int i = 0; i < count; i++) words.add("0x" + Integer.toUnsignedString(ByteBuffer.wrap(memory).order(ByteOrder.LITTLE_ENDIAN).getInt(offset + i * 4), 16));
                            reply = words.toString();
                        } else throw new AssertionError("Unexpected target command: " + command);
                        socket.getOutputStream().write((reply + '\u001a').getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
                    }
                } catch (IOException ex) { if (!server.isClosed()) throw new RuntimeException(ex); }
            }, "mock-variable-ram");
            worker.setDaemon(true); worker.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception { server.close(); worker.join(1000); }
    }
}
