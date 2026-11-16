package cn.crb.wavescope.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScopeSession implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "stm32-wave-sampler"); thread.setDaemon(true); return thread;
    });
    private final OpenOcdClient client;
    private final List<GdbResolver.Watch> watches;
    private final List<OpenOcdClient.Block> blocks;
    private final Path elf;
    private final long elfTime, elfSize, start = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean();
    public final SampleBuffer buffer;
    public volatile long frames, errors;
    public volatile String lastError = "";
    public volatile double readMilliseconds, actualHz;
    private long nextElfCheck, rateTime = start, rateFrames;
    private int consecutiveErrors;
    private int periodMilliseconds;
    private boolean fastSampling;

    public ScopeSession(OpenOcdClient client, List<GdbResolver.Watch> watches, Path elf) throws IOException {
        this.client = client; this.watches = List.copyOf(watches); this.elf = elf;
        blocks = OpenOcdClient.plan(watches);
        elfTime = elf == null ? 0 : Files.getLastModifiedTime(elf).toMillis();
        elfSize = elf == null ? 0 : Files.size(elf);
        buffer = new SampleBuffer(watches.size(), 20000);
    }

    public void start(int periodMs) {
        start(periodMs, false);
    }

    public void start(int periodMs, boolean fastSampling) {
        if (periodMs < 0 || periodMs > 10000 || (periodMs == 0 && !fastSampling)) {
            throw new IllegalArgumentException("采样周期范围为 1～10000 ms；高速采样可设 0 表示满速读取");
        }
        periodMilliseconds = periodMs;
        this.fastSampling = fastSampling;
        executor.schedule(this::poll, 0, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        if (closed.get()) return;
        long now = System.nanoTime();
        double seconds = (now - start) / 1e9;
        try {
            if (elf != null && now >= nextElfCheck) {
                nextElfCheck = now + 1_000_000_000L;
                if (Files.getLastModifiedTime(elf).toMillis() != elfTime || Files.size(elf) != elfSize) {
                    lastError = "ELF 已改变，已停止。请下载匹配固件，再重新开始以解析新地址。";
                    buffer.gap(seconds); close(); return;
                }
            }
            double[] values;
            if (client == null) {
                values = new double[watches.size()];
                for (int i = 0; i < values.length; i++) values[i] = switch (i % 4) {
                    case 0 -> 24 + 2 * Math.sin(seconds * 1.3);
                    case 1 -> 60 + 6 * Math.sin(seconds * 1.3 + 0.6);
                    case 2 -> 3 * Math.sin(seconds * 2.1);
                    default -> seconds % 4 < 2 ? 1 : 0;
                };
            } else values = client.sample(watches, blocks, isMaximumSpeed());
            buffer.add(seconds, values); frames++; consecutiveErrors = 0;
            lastError = "";
        } catch (Exception ex) {
            if (closed.get()) return;
            buffer.gap(seconds); errors++; consecutiveErrors++;
            lastError = message(ex);
            // A socket timeout makes Tcl request framing uncertain. Reconnect explicitly.
            // Other read failures are also stopped promptly; no hidden halt/retry loop.
            if (ex instanceof IOException || consecutiveErrors >= 3) close();
        } finally {
            readMilliseconds = (System.nanoTime() - now) / 1e6;
            long elapsed = System.nanoTime() - rateTime;
            if (elapsed >= 1_000_000_000L) {
                actualHz = (frames - rateFrames) * 1e9 / elapsed;
                rateFrames = frames; rateTime = System.nanoTime();
            }
            long readNanos = System.nanoTime() - now;
            // Stable mode retains the previous bus budget. Fast mode follows the requested
            // period, with a small yield after slow frames instead of catch-up bursts.
            long idleNanos = fastSampling ? 200_000L : Math.max(5_000_000L, readNanos);
            long delayNanos = Math.max(idleNanos, periodMilliseconds * 1_000_000L - readNanos);
            if (!closed.get()) {
                if (isMaximumSpeed() && client != null) {
                    // Exactly one frame in flight. Socket I/O supplies the wait; no spin loop
                    // and no backlog of requests. The demo retains a delay to bound CPU usage.
                    executor.execute(this::poll);
                } else if (fastSampling) {
                    // On this Windows JBR, the scheduled executor's timed park rounds small
                    // delays to ~15.6ms. Thread.sleep uses a finer timer; no busy-spin is needed.
                    executor.execute(() -> {
                        try { Thread.sleep(Duration.ofNanos(delayNanos)); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                        poll();
                    });
                } else executor.schedule(this::poll, delayNanos, TimeUnit.NANOSECONDS);
            }
        }
    }

    public boolean isClosed() { return closed.get(); }
    public boolean isFastSampling() { return fastSampling; }
    public boolean isMaximumSpeed() { return fastSampling && periodMilliseconds == 0; }
    public static String message(Throwable ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (client != null) try { client.close(); } catch (IOException ignored) { }
        executor.shutdownNow();
    }
}
