package cn.crb.wavescope;

import cn.crb.wavescope.core.OpenOcdClient;
import cn.crb.wavescope.core.GdbResolver;
import cn.crb.wavescope.core.ScalarType;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.List;

/** Tests the installed OpenOCD's actual Tcl framing, without initializing any probe or MCU. */
public final class OpenOcdProtocolTest {
    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = reservation.getLocalPort(); }
        Path log = Files.createTempFile("wave-openocd-noinit-", ".log");
        Process process = new ProcessBuilder(args[0], "-s", args[1], "-f", args[2],
                "-c", "noinit", "-c", "tcl port " + port, "-c", "telnet port disabled", "-c", "gdb port disabled")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean ready = false;
            for (int n = 0; n < 80 && process.isAlive(); n++) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 100); ready = true; break;
                } catch (IOException ex) { Thread.sleep(50); }
            }
            if (!ready) throw new AssertionError("OpenOCD Tcl server did not start: " + Files.readString(log));
            // In noinit mode this MUST fail. No automatic initialization/halt fallback is allowed.
            try (OpenOcdClient client = new OpenOcdClient("127.0.0.1", port)) {
                try { client.readMemory(0x20000000L, 4); throw new AssertionError("Unexpected real memory read before init"); }
                catch (IOException expected) { }
            }
            // Replace this test process's target command with a Tcl-only model. The actual probe
            // remains uninitialized. This catches command qualification and protocol differences
            // that a hand-written TCP mock cannot exercise.
            try (Socket control = new Socket("127.0.0.1", port)) {
                control.setSoTimeout(1500);
                String script = "rename stm32g4x.cpu wave_unused_target; "
                        + "set wave_test_ram [dict create]; proc stm32g4x.cpu {args} {"
                        + "if {[lindex $args 0] eq {write_memory}} {"
                        + "if {[lindex $args 1] == 0x20000124} {error {simulated write failed}}; "
                        + "dict set ::wave_test_ram [lindex $args 1] [lindex [lindex $args 3] 0]; return}; "
                        + "if {[lindex $args 0] ne {read_memory}} {error {unexpected command}}; "
                        + "if {[dict exists $::wave_test_ram [lindex $args 1]]} {return [list [dict get $::wave_test_ram [lindex $args 1]]]}; "
                        + "if {[lindex $args 1] == 0x20000120} {return {0x01234567}}; "
                        + "if {[lindex $args 1] == 0x20000124} {error {simulated RAM read failed}}; "
                        + "return {0x41900000}}";
                control.getOutputStream().write((script + '\u001a').getBytes(StandardCharsets.UTF_8));
                while (true) { int b = control.getInputStream().read(); if (b == 0x1a) break; if (b < 0) throw new EOFException(); }
            }
            try (OpenOcdClient client = new OpenOcdClient("127.0.0.1", port)) {
                byte[] data = client.readMemory(0x20000000L, 4);
                if (ScalarType.F32.decode(data, 0) != 18.0) throw new AssertionError("Real OpenOCD Tcl response mismatch");
                List<GdbResolver.Watch> watches = List.of(new GdbResolver.Watch("a", 0x20000000L, ScalarType.F32),
                        new GdbResolver.Watch("b", 0x20000120L, ScalarType.U32));
                double[] values = client.sample(watches, OpenOcdClient.plan(watches), true);
                if (values[0] != 18 || values[1] != 0x01234567) throw new AssertionError("Real OpenOCD batch framing mismatch");
                List<GdbResolver.Watch> failing = List.of(watches.getFirst(),
                        new GdbResolver.Watch("fail", 0x20000124L, ScalarType.U32));
                try {
                    client.sample(failing, OpenOcdClient.plan(failing), true);
                    throw new AssertionError("Later-block Tcl error was not detected");
                } catch (IOException expected) { }
                if (ScalarType.F32.decode(client.readMemory(0x20000000L, 4), 0) != 18) {
                    throw new AssertionError("Batch failure broke the next RPC frame");
                }
                if (ScalarType.F32.decode(client.writeScalar(0x20000000L, ScalarType.F32, "28.25"), 0) != 28.25) {
                    throw new AssertionError("Real OpenOCD scalar write/readback protocol mismatch");
                }
                try {
                    client.writeScalar(0x20000124L, ScalarType.U32, "1");
                    throw new AssertionError("Real OpenOCD write error was accepted");
                } catch (IOException expected) { }
            }
            if (!Files.readString(log).contains("must be used after 'init'")) throw new AssertionError("Expected target to remain uninitialized");
            System.out.println("PASS installed OpenOCD single/batch/read/write/error Tcl protocol; no probe/MCU initialization");
        } finally {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            Files.deleteIfExists(log);
        }
    }
}
