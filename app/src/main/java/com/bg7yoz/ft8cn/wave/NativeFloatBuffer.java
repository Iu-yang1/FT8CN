package com.bg7yoz.ft8cn.wave;

/** Q65 长时隙使用的 native-owned PCM 缓冲区，关闭后句柄立即失效。 */
public final class NativeFloatBuffer implements AutoCloseable {
    static {
        System.loadLibrary("ft8cn");
    }

    private long nativeHandle;

    public NativeFloatBuffer(int capacity) {
        nativeHandle = createNative(capacity);
        if (nativeHandle == 0L) {
            throw new IllegalArgumentException("无法分配 native PCM 缓冲区: " + capacity);
        }
    }

    public synchronized int append(float[] input, int offset, int count) {
        ensureOpen();
        int written = appendNative(nativeHandle, input, offset, count);
        if (written != count) {
            throw new IllegalStateException("native PCM 写入失败: " + written + "/" + count);
        }
        return written;
    }

    public synchronized int size() {
        ensureOpen();
        return sizeNative(nativeHandle);
    }

    public synchronized int capacity() {
        ensureOpen();
        return capacityNative(nativeHandle);
    }

    public synchronized long requireNativeHandle() {
        ensureOpen();
        return nativeHandle;
    }

    /** 仅供同包设备测试核对分块输出；生产解码不会创建该副本。 */
    synchronized float[] copyToArrayForTest() {
        ensureOpen();
        return copyNative(nativeHandle);
    }

    @Override
    public synchronized void close() {
        if (nativeHandle != 0L) {
            destroyNative(nativeHandle);
            nativeHandle = 0L;
        }
    }

    private void ensureOpen() {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("native PCM 缓冲区已关闭");
        }
    }

    private static native long createNative(int capacity);
    private static native int appendNative(long handle, float[] input, int offset, int count);
    private static native int sizeNative(long handle);
    private static native int capacityNative(long handle);
    private static native float[] copyNative(long handle);
    private static native void destroyNative(long handle);
}
