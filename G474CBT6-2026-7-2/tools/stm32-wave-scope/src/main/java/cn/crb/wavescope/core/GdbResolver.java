package cn.crb.wavescope.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GDB only opens the ELF locally. It never connects to the MCU. */
public final class GdbResolver {
    private static final Pattern EXPRESSION = Pattern.compile(
            "[A-Za-z_][A-Za-z_0-9]*(?:(?:::[A-Za-z_][A-Za-z_0-9]*)|(?:\\.[A-Za-z_][A-Za-z_0-9]*)|(?:\\[[0-9]+\\]))*");
    private static final Pattern ADDRESS = Pattern.compile("WAVE_ADDR=(0x[0-9a-fA-F]+)");
    private static final Pattern SIZE = Pattern.compile("WAVE_SIZE=([0-9]+)");
    private static final Pattern TYPE = Pattern.compile("(?s)type = (.*)");
    private static final Pattern ARRAY_SUFFIX = Pattern.compile("((?:\\s*\\[[0-9]+\\])+)\\s*$");
    private static final Pattern DIMENSION = Pattern.compile("\\[([0-9]+)\\]");

    public record Input(String expression, ScalarType type) { }
    public record Watch(String expression, long address, ScalarType type) { }
    public record Description(String expression, long address, int bytes, ScalarType type, List<Integer> dimensions,
                              List<String> members, boolean writable) {
        public Description(String expression, long address, int bytes, ScalarType type, List<Integer> dimensions) {
            this(expression, address, bytes, type, dimensions, List.of(), true);
        }
        public boolean isArray() { return !dimensions.isEmpty(); }
        public boolean isStruct() { return !members.isEmpty(); }
        public boolean isGroup() { return isArray() || isStruct(); }
        public Description element(int index) {
            if (!isArray() || index < 0 || index >= dimensions.getFirst()) throw new IllegalArgumentException("数组下标越界");
            int stride = bytes / dimensions.getFirst();
            return new Description(expression + "[" + index + "]", address + (long)index * stride,
                    stride, type, dimensions.subList(1, dimensions.size()), members, writable);
        }
    }
    public record Node(Description description, List<Node> children) { }

    public List<Node> inspectTree(Path gdb, Path elf, List<String> expressions) throws Exception {
        int[] remaining = {8192};
        List<Node> nodes = new ArrayList<>();
        for (Description info : inspect(gdb, elf, expressions)) nodes.add(expand(gdb, elf, info, 0, remaining));
        return List.copyOf(nodes);
    }

    private Node expand(Path gdb, Path elf, Description info, int depth, int[] remaining) throws Exception {
        if (--remaining[0] < 0 || depth > 12) throw new IllegalArgumentException("展开内容过多，请直接添加所需子成员");
        List<Node> children = new ArrayList<>();
        if (info.isArray()) {
            for (int i = 0; i < info.dimensions().getFirst(); i++) children.add(expand(gdb, elf, info.element(i), depth + 1, remaining));
        } else if (info.isStruct()) {
            for (int first = 0; first < info.members().size(); first += 32) {
                List<String> names = info.members().subList(first, Math.min(first + 32, info.members().size())).stream()
                        .map(name -> info.expression() + "." + name).toList();
                for (Description child : inspect(gdb, elf, names)) {
                    if (!info.writable()) child = new Description(child.expression(), child.address(), child.bytes(), child.type(),
                            child.dimensions(), child.members(), false);
                    children.add(expand(gdb, elf, child, depth + 1, remaining));
                }
            }
        }
        return new Node(info, List.copyOf(children));
    }

    public static void validateExpression(String expression) {
        if (expression == null || expression.length() > 256 || !EXPRESSION.matcher(expression).matches()) {
            throw new IllegalArgumentException("仅支持全局变量、成员和常量下标，例如 adc_data.volt.V_CAP 或 values[0]");
        }
    }

    public List<Watch> resolve(Path gdb, Path elf, List<Input> inputs) throws Exception {
        List<Description> descriptions = inspect(gdb, elf, inputs.stream().map(Input::expression).toList());
        List<Watch> watches = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Description description = descriptions.get(i);
            if (description.isGroup()) throw new IllegalArgumentException("请展开结构体或数组并选择具体数值: " + description.expression());
            ScalarType scalar = inputs.get(i).type() == ScalarType.AUTO ? description.type() : inputs.get(i).type();
            if (scalar.bytes != description.bytes()) throw new IOException(description.expression() + " 大小为 " + description.bytes() + " 字节，与所选类型不符");
            watches.add(new Watch(description.expression(), description.address(), scalar));
        }
        return List.copyOf(watches);
    }

    /** Inspect arrays without executing target code or reading target memory. */
    public List<Description> inspect(Path gdb, Path elf, List<String> expressions) throws Exception {
        if (!Files.isRegularFile(gdb)) throw new IOException("找不到 GDB: " + gdb);
        if (!Files.isRegularFile(elf)) throw new IOException("找不到 ELF: " + elf);
        if (expressions.isEmpty() || expressions.size() > 32) throw new IllegalArgumentException("请选择 1～32 个变量");
        List<String> command = new ArrayList<>(List.of(gdb.toString(), "-q", "-nx", "-nh", "-batch",
                "-iex", "set auto-load off", "--se=" + elf.toAbsolutePath()));
        StringBuilder script = new StringBuilder("set pagination off\n");
        for (int i = 0; i < expressions.size(); i++) {
            String expression = expressions.get(i);
            validateExpression(expression);
            script.append("echo WAVE_BEGIN_").append(i).append("\\n\n");
            // A command file avoids Windows/MinGW stripping nested quotes in -ex arguments.
            // GDB command files stop on an error, so a missing symbol aborts resolution.
            script.append("printf \"WAVE_ADDR=%p\\n\", &(").append(expression).append(")\n");
            script.append("printf \"WAVE_SIZE=%u\\n\", (unsigned int)sizeof(").append(expression).append(")\n");
            script.append("ptype ").append(expression).append('\n');
        }
        script.append("echo WAVE_END\\n\n");
        Path output = Files.createTempFile("stm32-wave-gdb-", ".log");
        Path commands = Files.createTempFile("stm32-wave-gdb-", ".gdb");
        Process process = null;
        String result;
        try {
            Files.writeString(commands, script, StandardCharsets.UTF_8);
            command.add("-x"); command.add(commands.toString());
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IOException("GDB 解析超时，请检查工具路径和 ELF");
            result = Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n");
            result = result.replaceAll("warning: could not convert[^\\n]*\\nThis normally should not happen[^\\n]*\\n", "");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
            Files.deleteIfExists(commands);
        }
        List<Description> descriptions = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            int start = result.indexOf("WAVE_BEGIN_" + i + "\n");
            String endMarker = i + 1 < expressions.size() ? "WAVE_BEGIN_" + (i + 1) + "\n" : "WAVE_END";
            int end = result.indexOf(endMarker, Math.max(start, 0));
            if (start < 0 || end < start) throw new IOException("GDB 返回不完整: " + result);
            String block = result.substring(start, end);
            Matcher address = ADDRESS.matcher(block), size = SIZE.matcher(block), type = TYPE.matcher(block);
            if (!address.find() || !size.find() || !type.find()) {
                throw new IOException("无法解析 " + expressions.get(i) + ":\n" + block);
            }
            long location = Long.parseUnsignedLong(address.group(1).substring(2), 16);
            int bytes = Integer.parseInt(size.group(1));
            requireRam(location, bytes);
            String description = type.group(1).trim();
            List<Integer> dimensions = new ArrayList<>();
            Matcher suffix = ARRAY_SUFFIX.matcher(description);
            int elementBytes = bytes;
            if (suffix.find()) {
                Matcher dimension = DIMENSION.matcher(suffix.group(1));
                long elements = 1;
                while (dimension.find()) {
                    int length = Integer.parseInt(dimension.group(1));
                    if (length <= 0 || length > 4096 || elements > 4096L / length || dimensions.size() >= 8) {
                        throw new IllegalArgumentException("数组展开最多支持 4096 个数值元素，请添加某一行或具体元素: " + expressions.get(i));
                    }
                    elements *= length; dimensions.add(length);
                }
                if (bytes % elements != 0) throw new IOException("数组长度与存储大小不匹配: " + expressions.get(i));
                elementBytes = (int)(bytes / elements);
                description = description.substring(0, suffix.start()).trim();
            }
            boolean structure = description.matches("(?s)^(?:(?:const|volatile)\\s+)*(?:struct|union)\\b.*\\{.*");
            String qualifiers = description.contains("{") ? description.substring(0, description.indexOf('{')) : description;
            boolean writable = !Pattern.compile("\\bconst\\b").matcher(qualifiers).find();
            List<String> members = structure ? memberNames(description) : List.of();
            ScalarType scalar = structure ? ScalarType.AUTO : ScalarType.infer(description, elementBytes);
            if (structure && members.isEmpty()) throw new IllegalArgumentException("未找到可展开的普通成员: " + expressions.get(i));
            descriptions.add(new Description(expressions.get(i), location, bytes, scalar, List.copyOf(dimensions), members, writable));
        }
        return List.copyOf(descriptions);
    }

    /** Parse only top-level C field declarations; nested types are inspected separately by GDB. */
    public static List<String> memberNames(String type) {
        int opening = type.indexOf('{'), closing = type.lastIndexOf('}');
        if (opening < 0 || closing < opening) throw new IllegalArgumentException("无效结构体类型");
        List<String> names = new ArrayList<>();
        StringBuilder declaration = new StringBuilder(); int depth = 0;
        for (int i = opening + 1; i < closing; i++) {
            char c = type.charAt(i);
            if (c == '{') { depth++; continue; }
            if (c == '}') { depth--; continue; }
            if (depth != 0) continue;
            if (c != ';') { declaration.append(c); continue; }
            String field = declaration.toString().trim(); declaration.setLength(0);
            if (field.contains("*") || field.contains(":") || field.contains("(") || field.startsWith("static ")) continue;
            Matcher name = Pattern.compile("\\b([A-Za-z_][A-Za-z_0-9]*)\\s*(?:\\[[0-9]+\\]\\s*)*$").matcher(field);
            if (name.find()) names.add(name.group(1));
        }
        return List.copyOf(names);
    }

    public static void requireRam(long address, int size) {
        long end = address + size;
        if (size <= 0 || !((address >= 0x20000000L && end <= 0x40000000L)
                || (address >= 0x10000000L && end <= 0x10020000L))) {
            throw new IllegalArgumentException("只读取 STM32 RAM，禁止读取外设寄存器或 Flash: 0x" + Long.toHexString(address));
        }
    }

}
