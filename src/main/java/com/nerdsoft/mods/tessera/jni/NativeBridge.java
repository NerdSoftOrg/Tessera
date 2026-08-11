package com.nerdsoft.mods.tessera.jni;

import java.nio.ByteBuffer;

public final class NativeBridge {

    private NativeBridge() {
    }

    public static native ByteBuffer compressBC7(ByteBuffer rgba8Direct, int width, int height, int qualityPreset);

    public static native void releaseCompressed(ByteBuffer compressed);

    public static native boolean isBc1NativeAvailable();

    public static native ByteBuffer compressBC1(ByteBuffer rgba8Direct, int width, int height, int qualityPreset);

    public static native void releaseCompressedBC1(ByteBuffer compressed);

    public static native byte[] hashContent(ByteBuffer rgba8Direct, int length);

    public static native boolean isNativeAvailable();

    /**
     * JNI Signature Mapping:
     * (Ljava/nio/ByteBuffer;II[I[I[I[I[II)Ljava/nio/ByteBuffer;
     */
    public static native ByteBuffer detectFamiliesAndAssemble(
            ByteBuffer pixels,
            int[] srcOffsets,
            int[] widths,
            int[] heights,
            int[] destX,
            int[] destY,
            int[] tinted,
            int atlasWidth,
            int atlasHeight,
            int maxHammingDistance
    );

    public static native void releaseFamilyResult(ByteBuffer result);

    /**
     * Builds a full box-filtered mip chain from a single base-level RGBA8
     * buffer. See {@code build_mip_chain} in {@code lib.rs}/{@code family_detect.rs}
     * for the wire format and downsample algorithm. Returns {@code null}
     * (via a default/empty buffer, decoded by the caller as "unavailable")
     * on invalid input, matching the other native calls' failure convention.
     */
    public static native ByteBuffer buildMipChain(ByteBuffer rgba8Direct, int width, int height, int maxLevel);

    public static native void releaseMipChain(ByteBuffer result);
}
