package cn.crb.wavescope;

import cn.crb.wavescope.core.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** No extra dependencies. Tests transport behavior, real ELF resolution and sampling lifecycle. */
public final class CoreTests {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++; if (!condition) throw new AssertionError(label);
    }
    private static void rejects(Throwing action, String label) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException ex) { checks++; return; }
        throw new AssertionError(label);
    }
    private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        byte[] bytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(-3.25f).putInt(-1).putDouble(28.125).putShort((short)65535).array();
        check(ScalarType.F32.decode(bytes, 0) == -3.25, "float endian/sign");
        check(ScalarType.U32.decode(bytes, 4) == 4294967295.0, "u32 unsigned");
        check(ScalarType.F64.decode(bytes, 8) == 28.125, "double");
        check(ScalarType.U16.decode(bytes, 16) == 65535, "u16");
        check(ScalarType.infer("volatile float", 4) == ScalarType.F32, "infer volatile");
        check(ScalarType.infer("volatile enum {DISCHARGE=-1, CHARGE=1}", 4) == ScalarType.I32, "signed enum");
        rejects(() -> ScalarType.infer("float [4]", 16), "array rejected");
        rejects(() -> GdbResolver.validateExpression("f()"), "function rejected");
        rejects(() -> GdbResolver.validateExpression("x; quit"), "command injection rejected");
        rejects(() -> GdbResolver.validateExpression("*pointer"), "runtime pointer rejected");
        rejects(() -> GdbResolver.requireRam(0x40000000L, 4), "peripheral rejected");
        rejects(() -> GdbResolver.requireRam(0x08000000L, 4), "flash rejected");
        GdbResolver.validateExpression("adc_data.volt.V_CAP"); GdbResolver.validateExpression("ns::table[12].value");
        List<GdbResolver.Watch> watches = List.of(new GdbResolver.Watch("float_value", 0x20000000L, ScalarType.F32),
                new GdbResolver.Watch("unsigned_value", 0x20000004L, ScalarType.U32),
                new GdbResolver.Watch("distant", 0x20000120L, ScalarType.I16));
        check(OpenOcdClient.plan(watches).equals(List.of(new OpenOcdClient.Block(0x20000000L, 8),
                new OpenOcdClient.Block(0x20000120L, 2))), "RAM grouping");
        try (MockServer server = new MockServer(false); OpenOcdClient client = new OpenOcdClient("127.0.0.1", server.port())) {
            double[] values = client.sample(watches, OpenOcdClient.plan(watches));
            check(values[0] == -3.25 && values[1] == 4294967295.0 && values[2] == -123, "fragmented Tcl frames + decode");
            check(server.commands.size() == 2 && server.commands.stream().allMatch(c -> c.startsWith("[target current] read_memory ")), "read-only transport");
            byte[] unaligned = client.readMemory(0x20000001L, 5);
            check(Arrays.equals(unaligned, Arrays.copyOfRange(bytes, 1, 6)), "unaligned byte range extracted from aligned words");
            check(server.commands.getLast().equals("[target current] read_memory 0x20000000 32 2"), "single aligned block for unaligned range");
        }
        try (MockServer server = new MockServer(true); OpenOcdClient client = new OpenOcdClient("127.0.0.1", server.port())) {
            rejects(() -> client.readMemory(0x20000000L, 4), "target running read error must not become zero");
        }
        try (MockServer server = new MockServer(false); OpenOcdClient client = new OpenOcdClient("127.0.0.1", server.port())) {
            List<GdbResolver.Watch> mixed = List.of(watches.getFirst(),
                    new GdbResolver.Watch("unaligned", 0x20000001L, ScalarType.U16),
                    new GdbResolver.Watch("distant_byte", 0x20000121L, ScalarType.U8));
            double[] expected = client.sample(mixed, OpenOcdClient.plan(mixed));
            int before = server.commands.size();
            double[] batched = client.sample(mixed, OpenOcdClient.plan(mixed), true);
            check(Arrays.equals(batched, expected) && batched[0] == -3.25 && batched[1] == 20480 && batched[2] == 255,
                    "batched Tcl response preserves signed float and unaligned field decoding");
            check(server.commands.size() == before + 1 && server.commands.getLast().startsWith("join [list "),
                    "distant blocks share one Tcl RPC in maximum speed mode");
            before = server.commands.size();
            rejects(() -> client.sample(watches, List.of(new OpenOcdClient.Block(0x20000000L, 4),
                    new OpenOcdClient.Block(0x40000000L, 4)), true), "batch rejects peripheral reads");
            check(server.commands.size() == before, "invalid batch is rejected before sending any command");
        }
        SampleBuffer buffer = new SampleBuffer(1, 3);
        buffer.add(0, new double[]{1}); buffer.add(1, new double[]{2}); buffer.gap(2); buffer.add(3, new double[]{4});
        SampleBuffer.Snapshot snapshot = buffer.snapshot();
        check(snapshot.size() == 3 && snapshot.times()[0] == 1 && Double.isNaN(snapshot.values()[0][1]), "ring wrap + gap");
        StringWriter csv = new StringWriter(); SampleBuffer.writeCsv(csv, List.of("test"), snapshot);
        check(csv.toString().contains("2.000000,\n") && !csv.toString().contains("NaN"), "CSV gaps stay empty");
        buffer.clear(); check(buffer.snapshot().size() == 0, "clear");
        GdbResolver.Description matrix = new GdbResolver.Description("matrix", 0x20000200L, 24, ScalarType.F32, List.of(2, 3));
        check(matrix.element(1).dimensions().equals(List.of(3)) && matrix.element(1).bytes() == 12,
                "multidimensional array keeps the row shape and stride");
        check(matrix.element(1).element(2).address() == 0x20000214L
                        && matrix.element(1).element(2).expression().equals("matrix[1][2]"),
                "multidimensional scalar element has the correct address and expression");
        rejects(() -> matrix.element(2), "array child index bounds");
        try (MockServer server = new MockServer(false)) {
            ScopeSession session = new ScopeSession(new OpenOcdClient("127.0.0.1", server.port()), watches, null);
            session.start(10); Thread.sleep(130); session.close(); long count = session.frames;
            Thread.sleep(30);
            check(count >= 3 && session.frames == count && session.isClosed(), "session stop closes sampler");
        }
        long stableInterval;
        try (MockServer server = new MockServer(false, 25)) {
            ScopeSession session = new ScopeSession(new OpenOcdClient("127.0.0.1", server.port()), watches.subList(0, 1), null);
            session.start(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.frames < 4 && System.nanoTime() < deadline) Thread.sleep(10);
            session.close();
            boolean spaced = session.frames >= 4;
            for (int i = 1; i < server.requestTimes.size(); i++) {
                spaced &= server.requestTimes.get(i) - server.requestTimes.get(i - 1) >= TimeUnit.MILLISECONDS.toNanos(45);
            }
            check(spaced, "slow reads leave idle bus time even with a 2ms requested period");
            stableInterval = medianInterval(server.requestTimes);
        }
        try (MockServer server = new MockServer(false, 25)) {
            ScopeSession session = new ScopeSession(new OpenOcdClient("127.0.0.1", server.port()), watches.subList(0, 1), null);
            session.start(1, true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.frames < 6 && System.nanoTime() < deadline) Thread.sleep(10);
            session.close();
            long fastInterval = medianInterval(server.requestTimes);
            check(session.frames >= 6 && fastInterval < stableInterval * 0.85,
                    "fast mode at 1ms removes the stable mode idle budget on a slow transport");
            check(fastInterval >= TimeUnit.MILLISECONDS.toNanos(25) && session.errors == 0
                            && server.commands.stream().allMatch(c -> c.startsWith("[target current] read_memory ")),
                    "fast mode stays serial and read-only without catch-up bursts");
            System.out.printf(Locale.ROOT, "MOCK 25ms read: stable median %.2f ms, fast median %.2f ms (not hardware throughput)%n",
                    stableInterval / 1e6, fastInterval / 1e6);
        }
        double serialFrameNanos;
        try (MockServer server = new MockServer(false, 25); OpenOcdClient client = new OpenOcdClient("127.0.0.1", server.port())) {
            long before = System.nanoTime();
            for (int i = 0; i < 4; i++) client.sample(watches, OpenOcdClient.plan(watches));
            serialFrameNanos = (System.nanoTime() - before) / 4.0;
        }
        try (MockServer server = new MockServer(false, 25)) {
            ScopeSession session = new ScopeSession(new OpenOcdClient("127.0.0.1", server.port()), watches, null);
            session.start(0, true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (session.frames < 6 && System.nanoTime() < deadline) Thread.sleep(10);
            session.close();
            long interval = medianInterval(server.requestTimes);
            check(session.frames >= 6 && interval < serialFrameNanos * 0.85,
                    "maximum speed mode batches multiple blocks and reduces simulated round trips");
            check(session.errors == 0 && server.commands.stream().allMatch(c -> c.startsWith("join [list ")),
                    "maximum speed sessions use one read-only batch per frame");
            check(session.isMaximumSpeed() && session.isClosed(), "maximum speed session stops normally");
            System.out.printf(Locale.ROOT, "MOCK 25ms/RPC, two blocks: serial frame %.2f ms, maximum speed %.2f ms (not hardware throughput)%n",
                    serialFrameNanos / 1e6, interval / 1e6);
        }
        try (MockServer server = new MockServer(false)) {
            server.failAddress = 0x20000120L;
            ScopeSession session = new ScopeSession(new OpenOcdClient("127.0.0.1", server.port()), watches, null);
            session.start(0, true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!session.isClosed() && System.nanoTime() < deadline) Thread.sleep(10);
            check(session.isClosed() && session.errors == 1 && session.frames == 0,
                    "failure in a later batch block rejects the whole frame and stops");
            check(Double.isNaN(session.buffer.snapshot().values()[0][0]), "partial batch data stays a gap");
            session.close();
        }
        for (boolean fast : new boolean[]{false, true}) try (MockServer server = new MockServer(true)) {
            ScopeSession session = new ScopeSession(new OpenOcdClient("127.0.0.1", server.port()), watches, null);
            session.start(5, fast); Thread.sleep(80);
            check(session.isClosed() && session.errors == 1 && session.frames == 0, "read failure stops and records gap");
            check(Double.isNaN(session.buffer.snapshot().values()[0][0]), "session error gap"); session.close();
        }
        Path temporaryElf = Files.createTempFile("wave-elf-change-", ".elf");
        try {
            ScopeSession session = new ScopeSession(null, watches, temporaryElf);
            Files.writeString(temporaryElf, "new firmware"); session.start(5); Thread.sleep(80);
            check(session.isClosed() && session.lastError.contains("ELF"), "changed ELF invalidates addresses"); session.close();
        } finally { Files.deleteIfExists(temporaryElf); }
        if (args.length == 2) {
            List<GdbResolver.Input> inputs = List.of(new GdbResolver.Input("adc_data.volt.V_CAP", ScalarType.AUTO),
                    new GdbResolver.Input("adc_data.adc_data_raw[0]", ScalarType.AUTO),
                    new GdbResolver.Input("loop_var.energy_direction", ScalarType.AUTO),
                    new GdbResolver.Input("loop_var.pwm_enabled", ScalarType.AUTO),
                    new GdbResolver.Input("adc_data.power.P_BAT", ScalarType.AUTO),
                    new GdbResolver.Input("adc_data.power.P_CHASS", ScalarType.AUTO),
                    new GdbResolver.Input("loop_var.I_target", ScalarType.AUTO));
            List<GdbResolver.Watch> resolved = new GdbResolver().resolve(Path.of(args[0]), Path.of(args[1]), inputs);
            check(resolved.size() == 7 && resolved.get(0).type() == ScalarType.F32 && resolved.get(0).address() >= 0x20000000L, "real firmware ELF float");
            check(resolved.get(1).type() == ScalarType.U32 && resolved.get(2).type() == ScalarType.I8
                    && resolved.get(3).type() == ScalarType.U8, "real ELF array + enum + typedef");
            for (GdbResolver.Watch watch : resolved) System.out.printf("ELF %s = 0x%08X %s%n", watch.expression(), watch.address(), watch.type());
            rejects(() -> new GdbResolver().resolve(Path.of(args[0]), Path.of(args[1]),
                    List.of(new GdbResolver.Input("no_such_global", ScalarType.AUTO))), "missing symbol error");
            List<GdbResolver.Description> arrays = new GdbResolver().inspect(Path.of(args[0]), Path.of(args[1]),
                    List.of("adc_data.adc_data_raw", "adc_data.adc_data_mid", "PI_Volt_Controller.error"));
            check(arrays.get(0).dimensions().equals(List.of(2)) && arrays.get(0).type() == ScalarType.U32,
                    "real ELF volatile uint32 array length and type");
            check(arrays.get(1).dimensions().equals(List.of(4)) && arrays.get(1).type() == ScalarType.U16,
                    "real ELF uint16 array length and type");
            check(arrays.get(2).dimensions().equals(List.of(2)) && arrays.get(2).type() == ScalarType.F32,
                    "real ELF struct float array length and type");
            check(arrays.get(0).element(1).address() == resolved.get(1).address() + 4,
                    "array expansion agrees with an independently resolved element address");
            rejects(() -> new GdbResolver().resolve(Path.of(args[0]), Path.of(args[1]),
                    List.of(new GdbResolver.Input("adc_data.adc_data_raw", ScalarType.AUTO))), "unexpanded array cannot become a scalar watch");
        }
        System.out.println("PASS " + checks + " core checks");
    }

    private static long medianInterval(List<Long> times) {
        if (times.size() < 2) throw new AssertionError("Insufficient samples");
        long[] intervals = new long[times.size() - 1];
        for (int i = 1; i < times.size(); i++) intervals[i - 1] = times.get(i) - times.get(i - 1);
        Arrays.sort(intervals);
        return intervals[intervals.length / 2];
    }

    private static final class MockServer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final List<Long> requestTimes = new CopyOnWriteArrayList<>();
        private final Thread worker;
        private volatile Socket connection;
        private volatile long failAddress = -1;
        MockServer(boolean error) throws IOException {
            this(error, 0);
        }
        MockServer(boolean error, int delayMs) throws IOException {
            worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setTcpNoDelay(true);
                    connection = socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
                    ByteArrayOutputStream command = new ByteArrayOutputStream();
                    int b;
                    while ((b = input.read()) >= 0) {
                        if (b != 0x1a) { command.write(b); continue; }
                        String request = command.toString(StandardCharsets.UTF_8); command.reset(); commands.add(request);
                        requestTimes.add(System.nanoTime());
                        if (delayMs > 0) Thread.sleep(delayMs);
                        var pattern = java.util.regex.Pattern.compile("\\[target current\\] read_memory 0x([0-9a-f]+) 32 ([0-9]+)");
                        var matcher = pattern.matcher(request);
                        List<String> reads = new ArrayList<>();
                        while (matcher.find()) reads.add(matcher.group());
                        String canonical = request.startsWith("join [list")
                                ? "join [list" + reads.stream().map(r -> " [" + r + "]").collect(java.util.stream.Collectors.joining()) + "] {|}"
                                : reads.size() == 1 ? reads.getFirst() : "";
                        if (reads.isEmpty() || !request.equals(canonical)) throw new AssertionError("Unexpected write/halt: " + request);
                        byte[] memory = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).putFloat(-3.25f).putInt(-1).array();
                        ByteBuffer.wrap(memory).order(ByteOrder.LITTLE_ENDIAN).putShort(0x120, (short)-123);
                        StringJoiner blocks = new StringJoiner("|");
                        String response = null;
                        for (String read : reads) {
                            matcher = pattern.matcher(read); matcher.matches();
                            long address = Long.parseLong(matcher.group(1), 16);
                            int count = Integer.parseInt(matcher.group(2));
                            if (error || address == failAddress) { response = "Error: target not examined"; break; }
                            StringJoiner reply = new StringJoiner(" ");
                            for (int n = 0; n < count; n++) {
                                int word = ByteBuffer.wrap(memory).order(ByteOrder.LITTLE_ENDIAN).getInt((int)(address - 0x20000000L) + n * 4);
                                reply.add(String.format("0x%08x", word));
                            }
                            blocks.add(reply.toString());
                        }
                        if (response == null) response = blocks.toString();
                        byte[] encoded = (response + '\u001a').getBytes(StandardCharsets.UTF_8);
                        int split = Math.min(7, encoded.length); output.write(encoded, 0, split); output.flush();
                        output.write(encoded, split, encoded.length - split); output.flush();
                    }
                } catch (IOException | InterruptedException ignored) { }
            }, "mock-openocd");
            worker.setDaemon(true); worker.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception {
            if (connection != null) connection.close(); server.close(); worker.join(1000);
        }
    }
}
