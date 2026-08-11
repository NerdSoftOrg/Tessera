package com.nerdsoft.mods.tessera.compress;

import com.nerdsoft.mods.tessera.cache.AtlasCache;
import com.nerdsoft.mods.tessera.config.TesseraConfig;
import com.nerdsoft.mods.tessera.jni.NativeBridge;
import com.nerdsoft.mods.tessera.jni.NativeLibraryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Runs the quality-preset / VRAM-budget-aware compression pipeline for a
 * single atlas bucket. Callers pass a {@link Target} (BC1 for the opaque
 * bucket, BC7 for the alpha bucket) so the bytes-per-block, native call,
 * and cache format all line up with what that bucket actually needs -- the
 * dynamic/RGBA8 bucket never goes through this class at all, since it
 * skips compression entirely.
 */
public final class CompressionPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/CompressionPipeline");
    private static final int BLOCK_TEXELS = 4;
    private final AtlasCache cache;

    public CompressionPipeline(AtlasCache cache) {
        this.cache = cache;
    }

    /**
     * Which block-compression format a given bucket compresses to.
     * Bytes-per-block differs by exactly 2x (BC1 is 8, BC7 is 16), which is
     * the whole reason the opaque bucket is worth splitting out in the
     * first place -- see {@link #bytesPerBlock()}.
     */
    public enum Target {
        BC1(8, AtlasCache.CompressedFormat.BC1),
        BC7(16, AtlasCache.CompressedFormat.BC7);

        private final int bytesPerBlock;
        private final AtlasCache.CompressedFormat cacheFormat;

        Target(int bytesPerBlock, AtlasCache.CompressedFormat cacheFormat) {
            this.bytesPerBlock = bytesPerBlock;
            this.cacheFormat = cacheFormat;
        }

        public int bytesPerBlock() {
            return bytesPerBlock;
        }

        public AtlasCache.CompressedFormat cacheFormat() {
            return cacheFormat;
        }
    }

    private static long projectedCompressedSize(int width, int height, Target target) {
        long blocksX = (width + BLOCK_TEXELS - 1) / BLOCK_TEXELS;
        long blocksY = (height + BLOCK_TEXELS - 1) / BLOCK_TEXELS;
        return blocksX * blocksY * target.bytesPerBlock();
    }

    public Optional<CompressionResult> compress(ByteBuffer rgba8Direct, int width, int height, Target target) {
        if (TesseraConfig.DISABLE_NATIVE_COMPRESSION.get() || !NativeLibraryLoader.isAvailable()) {
            return Optional.empty();
        }
        if (target == Target.BC7 && !Bc7GpuSupport.isSupported()) {
            return Optional.empty();
        }
        if (target == Target.BC1 && !NativeBridge.isBc1NativeAvailable()) {
            return Optional.empty();
        }

        int rgbaLength = width * height * 4;
        String hash = cache.hashHex(rgba8Direct, rgbaLength);

        Optional<CompressionResult> cacheHit = tryReadCache(hash, target);
        if (cacheHit.isPresent()) {
            return cacheHit;
        }

        int qualityPreset = resolveQualityPresetForBudget(width, height, target);

        ByteBuffer compressed = target == Target.BC1
                ? NativeBridge.compressBC1(rgba8Direct, width, height, qualityPreset)
                : NativeBridge.compressBC7(rgba8Direct, width, height, qualityPreset);
        if (compressed == null) {
            LOGGER.warn("Native {} compression call returned no result; falling back to vanilla atlas upload.", target);
            return Optional.empty();
        }

        try {
            ByteBuffer persisted = compressed.asReadOnlyBuffer();
            persisted.rewind();
            try {
                cache.write(hash, target.cacheFormat(), width, height, qualityPreset, persisted);
            } catch (IOException e) {
                LOGGER.warn("Failed to persist compressed atlas {} to disk cache.", hash, e);
            }

            ByteBuffer retained = ByteBuffer.allocateDirect(compressed.remaining());
            compressed.rewind();
            retained.put(compressed);
            retained.flip();

            return Optional.of(new CompressionResult(retained, qualityPreset, false));
        } finally {
            if (target == Target.BC1) {
                NativeBridge.releaseCompressedBC1(compressed);
            } else {
                NativeBridge.releaseCompressed(compressed);
            }
        }
    }

    private Optional<CompressionResult> tryReadCache(String hash, Target target) {
        try {
            return cache.read(hash, target.cacheFormat()).map(hit ->
                    new CompressionResult(hit.compressedBlocks(), hit.qualityPreset(), true));
        } catch (IOException e) {
            LOGGER.warn("Failed to read disk cache entry {}; recompressing.", hash, e);
            return Optional.empty();
        }
    }

    private int resolveQualityPresetForBudget(int width, int height, Target target) {
        int preset = TesseraConfig.COMPRESSION_QUALITY.get();
        long budgetBytes = TesseraConfig.VRAM_BUDGET_TARGET_MB.get() * 1024L * 1024L;
        long projectedBytes = projectedCompressedSize(width, height, target);
        int maxAttempts = TesseraConfig.MAX_QUALITY_STEP_DOWN_ATTEMPTS.get();

        int attempts = 0;
        while (projectedBytes > budgetBytes && preset > 0 && attempts < maxAttempts) {
            preset--;
            attempts++;
        }

        if (projectedBytes > budgetBytes) {
            LOGGER.warn(
                    "Atlas bucket ({}x{}, ~{} MB as {}) exceeds vramBudgetTargetMb ({} MB); {} is a fixed "
                            + "{}-bits-per-pixel format, so compressionQuality only affects encode fidelity here, "
                            + "not resident size. Lower dedupSimilarityThreshold or vramBudgetTargetMb expectations, "
                            + "or accept the overage.",
                    width, height, projectedBytes / (1024 * 1024), target, budgetBytes / (1024 * 1024),
                    target, target.bytesPerBlock() / 2
            );
        }

        return preset;
    }

    public record CompressionResult(ByteBuffer compressedBlocks, int qualityPresetUsed, boolean fromCache) {
    }
}