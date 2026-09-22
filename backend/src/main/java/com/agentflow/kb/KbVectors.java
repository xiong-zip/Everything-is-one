package com.agentflow.kb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量工具：float[] ↔ SQLite BLOB 的编解码与余弦相似度。
 *
 * 编码固定小端序（Little-Endian）：显式声明字节序，避免依赖 JVM 默认值，
 * 将来换运行时或用别的语言读库都不会错位。
 */
final class KbVectors {

    private KbVectors() {
    }

    /** float[] → 小端序 byte[]（长度 = dim × 4） */
    static byte[] encode(float[] vec) {
        if (vec == null || vec.length == 0) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(vec.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    /** 小端序 byte[] → float[]；空/非法输入返回 null */
    static float[] decode(byte[] blob) {
        if (blob == null || blob.length == 0 || blob.length % Float.BYTES != 0) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[blob.length / Float.BYTES];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    /** 余弦相似度（-1 ~ 1）；长度不一致或零向量返回 0 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
