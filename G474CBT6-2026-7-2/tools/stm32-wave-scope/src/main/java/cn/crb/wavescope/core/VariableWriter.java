package cn.crb.wavescope.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

/** Only invoked by the explicit Write action. Never halts, resets or retries a target write. */
public final class VariableWriter {
    public record Result(ScalarType type, byte[] requested, byte[] observed) {
        public boolean matches() { return Arrays.equals(requested, observed); }
    }
    public Result write(Path gdb, Path elf, String host, int port, String expression, String value) throws Exception {
        long time = Files.getLastModifiedTime(elf).toMillis(), size = Files.size(elf);
        GdbResolver.validateExpression(expression);
        List<String> prefixes = new ArrayList<>();
        for (int i = 0; i < expression.length(); i++) {
            if (expression.charAt(i) == '.' || expression.charAt(i) == '[') prefixes.add(expression.substring(0, i));
        }
        prefixes.add(expression);
        List<GdbResolver.Description> descriptions = new GdbResolver().inspect(gdb, elf, prefixes);
        GdbResolver.Description info = descriptions.getLast();
        if (info.isGroup() || descriptions.stream().anyMatch(d -> !d.writable())) {
            throw new IllegalArgumentException("请选择可写的普通数值成员，不能整体写入结构体、数组或 const 变量");
        }
        if (info.type().bytes > 4) throw new IllegalArgumentException("仅支持单次写入 8/16/32 位数值，不支持 64 位写入");
        byte[] requested = info.type().encode(value);
        if (Files.getLastModifiedTime(elf).toMillis() != time || Files.size(elf) != size) throw new IOException("ELF 已改变，请重新解析后再写入");
        try (OpenOcdClient client = new OpenOcdClient(host, port)) {
            return new Result(info.type(), requested, client.writeScalar(info.address(), info.type(), value));
        }
    }
}
