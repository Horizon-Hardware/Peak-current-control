package cn.crb.wavescope.core;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** OpenOCD Tcl RPC framing is ASCII SUB (0x1a). Sampling reads RAM; scalar writes are explicit. */
public final class OpenOcdClient implements AutoCloseable {
    private static final Pattern WORD_VALUE = Pattern.compile("(?:0[xX][0-9a-fA-F]{1,8}|-?[0-9]{1,10})");
    private final Socket socket = new Socket();
    private final InputStream input;
    private final OutputStream output;
    public record Block(long address, int size) { }

    public OpenOcdClient(String host, int port) throws IOException {
        try {
            socket.connect(new InetSocketAddress(host, port), 1500);
            socket.setSoTimeout(1000);
            socket.setTcpNoDelay(true);
            input = new BufferedInputStream(socket.getInputStream()); output = socket.getOutputStream();
        } catch (IOException ex) { socket.close(); throw ex; }
    }

    public byte[] readMemory(long address, int count) throws IOException {
        Block requested = new Block(address, count);
        Block aligned = align(requested);
        return decodeMemory(command(readCommand(aligned)), requested, aligned);
    }

    public byte[] writeScalar(long address, ScalarType type, String value) throws IOException {
        if (type.bytes != 1 && type.bytes != 2 && type.bytes != 4) throw new IllegalArgumentException("仅支持写入 8/16/32 位数值");
        GdbResolver.requireRam(address, type.bytes);
        if (address % type.bytes != 0) throw new IllegalArgumentException("变量地址未自然对齐，无法单次写入");
        byte[] bytes = type.encode(value);
        long bits = 0;
        for (int i = 0; i < bytes.length; i++) bits |= Byte.toUnsignedLong(bytes[i]) << (8 * i);
        String reply = command("[target current] write_memory 0x" + Long.toHexString(address) + " " + (type.bytes * 8)
                + " {0x" + Long.toHexString(bits) + "}; format {WAVE_WRITE_OK}");
        if (!reply.trim().equals("WAVE_WRITE_OK")) throw new IOException("OpenOCD 写入未确认: " + abbreviate(reply));
        return readMemory(address, bytes.length);
    }

    private static Block align(Block block) {
        long address = block.address(); int count = block.size();
        GdbResolver.requireRam(address, count);
        if (count > 256) throw new IllegalArgumentException("读取块不能超过 256 字节");
        // Read aligned words in one block. This also covers byte/halfword fields, without
        // issuing small SWD transfers for each byte. Never round a read into peripheral space.
        long alignedAddress = address & ~3L;
        int offset = (int)(address - alignedAddress);
        int wordCount = (offset + count + 3) / 4;
        GdbResolver.requireRam(alignedAddress, wordCount * 4);
        return new Block(alignedAddress, wordCount * 4);
    }

    private static String readCommand(Block aligned) {
        return "[target current] read_memory 0x" + Long.toHexString(aligned.address()) + " 32 " + (aligned.size() / 4);
    }

    private static byte[] decodeMemory(String response, Block requested, Block aligned) throws IOException {
        int wordCount = aligned.size() / 4;
        int offset = (int)(requested.address() - aligned.address());
        if (response.isBlank()) throw new IOException("OpenOCD 没有返回内存数据，请确认目标已初始化，并查看 OpenOCD 调试日志");
        String[] values = response.trim().split("\\s+");
        if (values.length != wordCount) throw new IOException("OpenOCD 读取失败: " + abbreviate(response));
        byte[] bytes = new byte[wordCount * 4];
        try {
            for (int i = 0; i < wordCount; i++) {
                String value = values[i];
                if (!WORD_VALUE.matcher(value).matches()) throw new NumberFormatException();
                long number = value.startsWith("0x") || value.startsWith("0X")
                        ? Long.parseLong(value.substring(2), 16) : Long.parseLong(value);
                if (number < Integer.MIN_VALUE || number > 0xffffffffL) throw new NumberFormatException();
                for (int j = 0; j < 4; j++) bytes[i * 4 + j] = (byte)(number >>> (j * 8));
            }
        } catch (NumberFormatException ex) { throw new IOException("OpenOCD 返回了无效数据: " + abbreviate(response)); }
        return Arrays.copyOfRange(bytes, offset, offset + requested.size());
    }

    private String command(String command) throws IOException {
        output.write((command + '\u001a').getBytes(StandardCharsets.UTF_8)); output.flush();
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        while (reply.size() < 65536) {
            int value = input.read();
            if (value == -1) throw new IOException("OpenOCD 已断开连接");
            if (value == 0x1a) return reply.toString(StandardCharsets.UTF_8);
            reply.write(value);
        }
        throw new IOException("OpenOCD 响应过大");
    }

    public double[] sample(List<GdbResolver.Watch> watches, List<Block> blocks) throws IOException {
        return sample(watches, blocks, false);
    }

    public double[] sample(List<GdbResolver.Watch> watches, List<Block> blocks, boolean batch) throws IOException {
        byte[][] data = new byte[blocks.size()][];
        if (batch && blocks.size() > 1) {
            if (blocks.size() > 32) throw new IllegalArgumentException("满速采样最多读取 32 个内存块");
            List<Block> aligned = blocks.stream().map(OpenOcdClient::align).toList();
            // One Tcl RPC per frame, with the same read-only RAM blocks as before. A failure
            // aborts the whole frame, so partial data is never plotted as a valid sample.
            StringBuilder request = new StringBuilder("join [list");
            for (Block block : aligned) request.append(" [").append(readCommand(block)).append(']');
            request.append("] {|}");
            String response = command(request.toString());
            String[] replies = response.split("\\|", -1);
            if (replies.length != blocks.size()) throw new IOException("OpenOCD 批量读取失败: " + abbreviate(response));
            for (int i = 0; i < blocks.size(); i++) data[i] = decodeMemory(replies[i], blocks.get(i), aligned.get(i));
        } else for (int i = 0; i < blocks.size(); i++) data[i] = readMemory(blocks.get(i).address(), blocks.get(i).size());
        double[] values = new double[watches.size()];
        for (int i = 0; i < watches.size(); i++) {
            GdbResolver.Watch watch = watches.get(i);
            boolean found = false;
            for (int j = 0; j < blocks.size(); j++) {
                Block block = blocks.get(j);
                if (watch.address() >= block.address() && watch.address() + watch.type().bytes <= block.address() + block.size()) {
                    values[i] = watch.type().decode(data[j], (int)(watch.address() - block.address()));
                    found = true; break;
                }
            }
            if (!found) throw new IllegalArgumentException("变量不在读取计划中: " + watch.expression());
        }
        return values;
    }

    public static List<Block> plan(List<GdbResolver.Watch> watches) {
        List<GdbResolver.Watch> sorted = new ArrayList<>(watches);
        sorted.sort(Comparator.comparingLong(GdbResolver.Watch::address));
        List<Block> blocks = new ArrayList<>();
        long start = -1, end = -1;
        for (GdbResolver.Watch watch : sorted) {
            GdbResolver.requireRam(watch.address(), watch.type().bytes);
            long nextEnd = watch.address() + watch.type().bytes;
            if (start >= 0 && watch.address() <= end + 16 && Math.max(end, nextEnd) - start <= 256) {
                end = Math.max(end, nextEnd);
            } else {
                if (start >= 0) blocks.add(new Block(start, (int)(end - start)));
                start = watch.address(); end = nextEnd;
            }
        }
        if (start >= 0) blocks.add(new Block(start, (int)(end - start)));
        return List.copyOf(blocks);
    }

    private static String abbreviate(String text) { return text.length() > 500 ? text.substring(0, 500) : text; }
    @Override public void close() throws IOException { socket.close(); }
}
