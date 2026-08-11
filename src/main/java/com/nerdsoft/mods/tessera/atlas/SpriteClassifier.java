package com.nerdsoft.mods.tessera.atlas;

import com.nerdsoft.mods.tessera.config.TesseraConfig;
import com.nerdsoft.mods.tessera.jni.NativeLibraryLoader;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class SpriteClassifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SpriteClassifier");

    private SpriteClassifier() {
    }

    public record ClassificationResult(Map<SpriteBucket, List<SpriteContents>> buckets) {
        public List<SpriteContents> bucket(SpriteBucket bucket) {
            return buckets.getOrDefault(bucket, List.of());
        }
    }

    public static ClassificationResult classify(List<SpriteContents> allSprites) {
        Map<SpriteBucket, List<SpriteContents>> buckets = new EnumMap<>(SpriteBucket.class);
        for (SpriteBucket bucket : SpriteBucket.values()) {
            buckets.put(bucket, new ArrayList<>());
        }
        List<SpriteContents> staticSprites = new ArrayList<>(allSprites.size());
        for (SpriteContents contents : allSprites) {
            if (contents.createTicker() != null) {
                buckets.get(SpriteBucket.DYNAMIC_RGBA8).add(contents);
            } else {
                staticSprites.add(contents);
            }
        }
        if (staticSprites.isEmpty()) {
            return new ClassificationResult(buckets);
        }
        NativeFamilyDetector.AlphaShape[] shapes = classifyAlphaShapesViaNative(staticSprites);
        if (shapes == null) {
            LOGGER.warn(
                    "Native alpha classification unavailable; routing all {} static sprites to the BC7 bucket "
                            + "as a safe fallback (no opaque/BC7 split this stitch).",
                    staticSprites.size());
            buckets.get(SpriteBucket.ALPHA_BC7).addAll(staticSprites);
            return new ClassificationResult(buckets);
        }
        int opaqueCount = 0;
        int punchThroughCount = 0;
        int blendedCount = 0;
        for (int i = 0; i < staticSprites.size(); i++) {
            SpriteContents contents = staticSprites.get(i);
            switch (shapes[i]) {
                case FULLY_OPAQUE -> {
                    buckets.get(SpriteBucket.OPAQUE_BC1).add(contents);
                    opaqueCount++;
                }
                case PUNCH_THROUGH -> {
                    buckets.get(SpriteBucket.ALPHA_BC7).add(contents);
                    punchThroughCount++;
                }
                case BLENDED -> {
                    buckets.get(SpriteBucket.ALPHA_BC7).add(contents);
                    blendedCount++;
                }
            }
        }
        LOGGER.info(
                "Classified {} sprites: {} opaque (BC1), {} alpha ({} punch-through + {} blended, all BC7 for now), {} dynamic (RGBA8).",
                allSprites.size(), opaqueCount, punchThroughCount + blendedCount, punchThroughCount, blendedCount,
                buckets.get(SpriteBucket.DYNAMIC_RGBA8).size());
        return new ClassificationResult(buckets);
    }

    private static NativeFamilyDetector.AlphaShape[] classifyAlphaShapesViaNative(List<SpriteContents> staticSprites) {
        if (TesseraConfig.DISABLE_NATIVE_COMPRESSION.get() || !NativeLibraryLoader.isAvailable()) {
            return null;
        }
        SpriteLayoutTable layout;
        try {
            layout = SpriteLayoutTable.allocate(staticSprites);
        } catch (OutOfMemoryError | IllegalArgumentException e) {
            LOGGER.warn("Failed to allocate pixel buffer for alpha classification; skipping native classification.", e);
            return null;
        }
        layout.packPixels(staticSprites);
        NativeFamilyDetector.DetectionResult result = NativeFamilyDetector.detect(
                layout.pixels(), layout.toUntintedSpriteInputs(), 1, 1, TesseraConfig.DEDUP_SIMILARITY_THRESHOLD.get());
        if (result == null) {
            return null;
        }
        return result.alphaShapes();
    }
}