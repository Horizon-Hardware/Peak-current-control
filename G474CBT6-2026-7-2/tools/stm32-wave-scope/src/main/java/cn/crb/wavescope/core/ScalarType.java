package cn.crb.wavescope.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.math.BigInteger;

public enum ScalarType {
    AUTO(0), F32(4), F64(8), I8(1), U8(1), I16(2), U16(2), I32(4), U32(4), I64(8), U64(8), BOOL(1);

    public final int bytes;
    ScalarType(int bytes) { this.bytes = bytes; }

    /** Exact integer bounds and finite floats; no GDB expressions or executable input. */
    public byte[] encode(String text) {
        if (this == AUTO) throw new IllegalArgumentException("请先解析数值类型");
        String value = text.trim();
        ByteBuffer b = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (this == F32) {
                float number = Float.parseFloat(value);
                if (!Float.isFinite(number)) throw new IllegalArgumentException("只支持有限浮点数");
                b.putFloat(number);
            } else if (this == F64) {
                double number = Double.parseDouble(value);
                if (!Double.isFinite(number)) throw new IllegalArgumentException("只支持有限浮点数");
                b.putDouble(number);
            } else if (this == BOOL) {
                if (value.equalsIgnoreCase("true") || value.equals("1")) b.put((byte)1);
                else if (value.equalsIgnoreCase("false") || value.equals("0")) b.put((byte)0);
                else throw new IllegalArgumentException("布尔值请输入 true/false 或 1/0");
            } else {
                boolean negative = value.startsWith("-");
                String digits = value.replaceFirst("^[+-]", "");
                int radix = digits.startsWith("0x") || digits.startsWith("0X") ? 16 : 10;
                if (radix == 16) digits = digits.substring(2);
                if (!digits.matches(radix == 16 ? "[0-9a-fA-F]+" : "[0-9]+")) throw new NumberFormatException();
                BigInteger number = new BigInteger(digits, radix);
                if (negative) number = number.negate();
                boolean signed = name().startsWith("I");
                BigInteger min = signed ? BigInteger.ONE.shiftLeft(bytes * 8 - 1).negate() : BigInteger.ZERO;
                BigInteger max = BigInteger.ONE.shiftLeft(bytes * 8 - (signed ? 1 : 0)).subtract(BigInteger.ONE);
                if (number.compareTo(min) < 0 || number.compareTo(max) > 0) throw new IllegalArgumentException("数值超出 " + this + " 范围");
                long bits = number.longValue();
                for (int i = 0; i < bytes; i++) b.put((byte)(bits >>> (i * 8)));
            }
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("请输入有效数值，整数支持 0x 十六进制"); }
        return b.array();
    }

    public double decode(byte[] data, int offset) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return switch (this) {
            case F32 -> b.getFloat(offset);
            case F64 -> b.getDouble(offset);
            case I8 -> b.get(offset);
            case U8 -> Byte.toUnsignedInt(b.get(offset));
            case I16 -> b.getShort(offset);
            case U16 -> Short.toUnsignedInt(b.getShort(offset));
            case I32 -> b.getInt(offset);
            case U32 -> Integer.toUnsignedLong(b.getInt(offset));
            case I64 -> b.getLong(offset);
            case U64 -> { long n = b.getLong(offset); yield n >= 0 ? n : (double)(n & Long.MAX_VALUE) + 0x1.0p63; }
            case BOOL -> b.get(offset) == 0 ? 0 : 1;
            case AUTO -> throw new IllegalStateException("Unresolved AUTO type");
        };
    }

    public static ScalarType infer(String description, int size) {
        String t = description.toLowerCase(Locale.ROOT).replaceAll("\\b(const|volatile|restrict)\\b", " ").trim();
        if (t.contains("*") || t.contains("[") || t.contains("struct ") || t.contains("union ")) {
            throw new IllegalArgumentException("请选择具体数值字段或数组元素，不能直接画结构体、数组或指针");
        }
        if (t.matches("(?s).*\\bfloat\\b.*") && size == 4) return F32;
        if (t.matches("(?s).*\\bdouble\\b.*") && size == 8) return F64;
        if (t.matches("(?s).*\\b(_bool|bool)\\b.*") && size == 1) return BOOL;
        // ARM EABI enums are represented as integers; values are decoded using their storage size.
        boolean unsigned = t.contains("unsigned") || t.matches("(?s).*\\buint[0-9]+_t\\b.*");
        if (t.startsWith("enum ") && !t.matches("(?s).*=\\s*-.*")) unsigned = true;
        if (!t.matches("(?s).*\\b(char|short|int|long|signed|unsigned|enum|u?int[0-9]+_t)\\b.*")) {
            throw new IllegalArgumentException("不能自动识别类型，请在表格中选择 F32 / I32 等类型: " + description);
        }
        return switch (size) {
            case 1 -> unsigned ? U8 : I8;
            case 2 -> unsigned ? U16 : I16;
            case 4 -> unsigned ? U32 : I32;
            case 8 -> unsigned ? U64 : I64;
            default -> throw new IllegalArgumentException("不支持的标量大小: " + size);
        };
    }
}
