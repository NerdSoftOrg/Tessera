package com.nerdsoft.mods.tessera.atlas;

import com.nerdsoft.mods.tessera.jni.NativeBridge;
import com.nerdsoft.mods.tessera.jni.WireFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class NativeFamilyDetector {
    private NativeFamilyDetector() {
    }

    public record SpriteInput(int srcOffset, int width, int height, int destX, int destY, boolean tinted) {
    }

    public record Family(int representativeIndex, List<Integer> memberIndices) {
    }

    public enum AlphaShape {
        FULLY_OPAQUE,
        PUNCH_THROUGH,
        BLENDED
    }

    public record DetectionResult(
            long[] fingerprints, List<Family> families, boolean[] alphaFlags, AlphaShape[] alphaShapes,
            ByteBuffer atlasBuffer
    ) {
    }

    public static DetectionResult detect(
            ByteBuffer pixels,
            List<SpriteInput> sprites,
            int atlasWidth,
            int atlasHeight,
            int maxHammingDistance
    ) {
        int count = sprites.size();
        int[] srcOffsets = new int[count];
        int[] widths = new int[count];
        int[] heights = new int[count];
        int[] destX = new int[count];
        int[] destY = new int[count];
        int[] tinted = new int[count];
        for (int i = 0; i < count; i++) {
            SpriteInput sprite = sprites.get(i);
            srcOffsets[i] = sprite.srcOffset();
            widths[i] = sprite.width();
            heights[i] = sprite.height();
            destX[i] = sprite.destX();
            destY[i] = sprite.destY();
            tinted[i] = sprite.tinted() ? 1 : 0;
        }
        ByteBuffer raw = NativeBridge.detectFamiliesAndAssemble(
                pixels, srcOffsets, widths, heights, destX, destY, tinted,
                atlasWidth, atlasHeight, maxHammingDistance
        );
        if (raw == null || raw.capacity() == 0) {
            return null;
        }
        try {
            return decode(raw, count, atlasWidth, atlasHeight);
        } finally {
            NativeBridge.releaseFamilyResult(raw);
        }
    }

    private static DetectionResult decode(ByteBuffer raw, int spriteCount, int atlasWidth, int atlasHeight) {
        ByteBuffer wire = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        wire.rewind();
        long[] fingerprints = readFingerprints(wire, spriteCount);
        List<Family> families = readFamilies(wire);
        boolean[] alphaFlags = readAlphaFlags(wire, spriteCount);
        AlphaShape[] alphaShapes = readAlphaShapes(wire, spriteCount);
        ByteBuffer atlasBuffer = readAtlasBuffer(wire, atlasWidth, atlasHeight);
        return new DetectionResult(fingerprints, families, alphaFlags, alphaShapes, atlasBuffer);
    }

    private static long[] readFingerprints(ByteBuffer wire, int spriteCount) {
        long[] fingerprints = new long[spriteCount];
        for (int i = 0; i < spriteCount; i++) {
            fingerprints[i] = wire.getLong();
        }
        return fingerprints;
    }

    private static List<Family> readFamilies(ByteBuffer wire) {
        int familyCount = wire.getInt();
        List<Family> families = new ArrayList<>(familyCount);
        for (int i = 0; i < familyCount; i++) {
            int representativeIndex = wire.getInt();
            int memberCount = wire.getInt();
            List<Integer> memberIndices = new ArrayList<>(memberCount);
            for (int j = 0; j < memberCount; j++) {
                memberIndices.add(wire.getInt());
            }
            families.add(new Family(representativeIndex, memberIndices));
        }
        return families;
    }

    private static boolean[] readAlphaFlags(ByteBuffer wire, int spriteCount) {
        boolean[] alphaFlags = new boolean[spriteCount];
        for (int i = 0; i < spriteCount; i++) {
            alphaFlags[i] = wire.get() != 0;
        }
        return alphaFlags;
    }

    private static AlphaShape[] readAlphaShapes(ByteBuffer wire, int spriteCount) {
        AlphaShape[] shapes = new AlphaShape[spriteCount];
        for (int i = 0; i < spriteCount; i++) {
            int tag = wire.get();
            shapes[i] = switch (tag) {
                case 0 -> AlphaShape.FULLY_OPAQUE;
                case 1 -> AlphaShape.PUNCH_THROUGH;
                default -> AlphaShape.BLENDED;
            };
        }
        return shapes;
    }

    private static ByteBuffer readAtlasBuffer(ByteBuffer wire, int atlasWidth, int atlasHeight) {
        int atlasBytes = atlasWidth * atlasHeight * 4;
        return WireFormat.readLengthPrefixedBuffer(wire, atlasBytes);
    }
}