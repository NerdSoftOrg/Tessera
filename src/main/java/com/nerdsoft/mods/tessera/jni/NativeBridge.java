package com.nerdsoft.mods.tessera.jni;

import java.nio.ByteBuffer;

public final class NativeBridge {

    private NativeBridge() {
    }

    public static native ByteBuffer compressBC7(ByteBuffer rgba8Direct, int width, int height, int qualityPreset);

    public static native void releaseCompressed(ByteBuffer compressed);

    public static native byte[] hashContent(ByteBuffer rgba8Direct, int length);

    public static native boolean isNativeAvailable();
}
