package com.nerdsoft.mods.tessera.jni;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Shared decode helpers for the little-endian wire formats produced by the
 * native bridge (see the wire layout comments in native/bridge/src/lib.rs).
 * Both {@code detectFamiliesAndAssemble} and {@code buildMipChain} end their
 * payload with a raw pixel/block buffer whose length is derived by the Java
 * caller (not stored on the wire), then copied into a fresh DirectByteBuffer
 * so the caller can release the native-backed source buffer independently of
 * the decoded result's lifetime.
 */
public final class WireFormat {
    private WireFormat() {
    }

    /**
     * Reads up to {@code expectedBytes} remaining bytes from {@code wire} into
     * a newly allocated little-endian DirectByteBuffer, flipped and ready to
     * read. If fewer bytes remain than expected (a short/corrupt payload),
     * copies only what's actually there rather than over-reading.
     * <p>
     * Does not advance {@code wire}'s own position -- callers that need to
     * keep decoding fields after this buffer (e.g. multiple mip levels packed
     * back-to-back) must advance the source position themselves using the
     * returned buffer's length.
     */
    public static ByteBuffer readLengthPrefixedBuffer(ByteBuffer wire, int expectedBytes) {
        ByteBuffer view = wire.slice();
        view.limit(Math.min(expectedBytes, view.remaining()));
        ByteBuffer copy = ByteBuffer.allocateDirect(view.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        copy.put(view);
        copy.flip();
        return copy;
    }
}