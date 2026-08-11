package com.nerdsoft.mods.tessera.atlas;

import com.nerdsoft.mods.tessera.jni.NativeBridge;
import com.nerdsoft.mods.tessera.jni.WireFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class MipChainBuilder {
    private MipChainBuilder() {
    }

    public record MipLevel(int width, int height, ByteBuffer rgba8) {
    }

    public static List<MipLevel> build(ByteBuffer baseRgba8, int baseWidth, int baseHeight, int maxLevel) {
        if (maxLevel <= 0) {
            return List.of(new MipLevel(baseWidth, baseHeight, baseRgba8));
        }
        ByteBuffer raw = NativeBridge.buildMipChain(baseRgba8, baseWidth, baseHeight, maxLevel);
        if (raw == null || raw.capacity() == 0) {
            return null;
        }
        try {
            return decode(raw);
        } finally {
            NativeBridge.releaseMipChain(raw);
        }
    }

    private static List<MipLevel> decode(ByteBuffer raw) {
        ByteBuffer wire = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        wire.rewind();
        int levelCount = wire.getInt();
        List<MipLevel> levels = new ArrayList<>(levelCount);
        for (int i = 0; i < levelCount; i++) {
            int levelWidth = wire.getInt();
            int levelHeight = wire.getInt();
            int levelBytes = levelWidth * levelHeight * 4;
            ByteBuffer levelBuffer = WireFormat.readLengthPrefixedBuffer(wire, levelBytes);
            levels.add(new MipLevel(levelWidth, levelHeight, levelBuffer));
            wire.position(wire.position() + levelBuffer.remaining());
        }
        return levels;
    }
}