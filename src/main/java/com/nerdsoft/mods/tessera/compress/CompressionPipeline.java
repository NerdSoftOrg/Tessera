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

public final class CompressionPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/CompressionPipeline");
    private static final int BC7_BYTES_PER_BLOCK = 16;
    private static final int BC7_BLOCK_TEXELS = 4;
    private final AtlasCache cache;

    public CompressionPipeline(AtlasCache cache) {
        this.cache = cache;
    }

    private static long projectedCompressedSize(int width, int height) {
        long blocksX = (width + BC7_BLOCK_TEXELS - 1) / BC7_BLOCK_TEXELS;
        long blocksY = (height + BC7_BLOCK_TEXELS - 1) / BC7_BLOCK_TEXELS;
        return blocksX * blocksY * BC7_BYTES_PER_BLOCK;
    }

    public Optional<CompressionResult> compress(ByteBuffer rgba8Direct, int width, int height) {
        if (TesseraConfig.DISABLE_NATIVE_COMPRESSION.get() || !NativeLibraryLoader.isAvailable()) {
            return Optional.empty();
        }
        if (!Bc7GpuSupport.isSupported()) {
            return Optional.empty();
        }

        int rgbaLength = width * height * 4;
        String hash = cache.hashHex(rgba8Direct, rgbaLength);

        Optional<CompressionResult> cacheHit = tryReadCache(hash);
        if (cacheHit.isPresent()) {
            return cacheHit;
        }

        int qualityPreset = resolveQualityPresetForBudget(width, height);

        ByteBuffer compressed = NativeBridge.compressBC7(rgba8Direct, width, height, qualityPreset);
        if (compressed == null) {
            LOGGER.warn("Native BC7 compression call returned no result; falling back to vanilla atlas upload.");
            return Optional.empty();
        }

        try {
            ByteBuffer persisted = compressed.asReadOnlyBuffer();
            persisted.rewind();
            try {
                cache.write(hash, width, height, qualityPreset, persisted);
            } catch (IOException e) {
                LOGGER.warn("Failed to persist compressed atlas {} to disk cache.", hash, e);
            }

            ByteBuffer retained = ByteBuffer.allocateDirect(compressed.remaining());
            compressed.rewind();
            retained.put(compressed);
            retained.flip();

            return Optional.of(new CompressionResult(retained, qualityPreset, false));
        } finally {
            NativeBridge.releaseCompressed(compressed);
        }
    }

    private Optional<CompressionResult> tryReadCache(String hash) {
        try {
            return cache.read(hash).map(hit ->
                    new CompressionResult(hit.compressedBlocks(), hit.qualityPreset(), true));
        } catch (IOException e) {
            LOGGER.warn("Failed to read disk cache entry {}; recompressing.", hash, e);
            return Optional.empty();
        }
    }

    private int resolveQualityPresetForBudget(int width, int height) {
        int preset = TesseraConfig.COMPRESSION_QUALITY.get();
        long budgetBytes = TesseraConfig.VRAM_BUDGET_TARGET_MB.get() * 1024L * 1024L;
        long projectedBytes = projectedCompressedSize(width, height);
        int maxAttempts = TesseraConfig.MAX_QUALITY_STEP_DOWN_ATTEMPTS.get();

        int attempts = 0;
        while (projectedBytes > budgetBytes && preset > 0 && attempts < maxAttempts) {
            preset--;
            attempts++;
        }

        if (projectedBytes > budgetBytes) {
            LOGGER.warn(
                    "Atlas ({}x{}, ~{} MB as BC7) exceeds vramBudgetTargetMb ({} MB); BC7 is a fixed 8-bits-per-pixel "
                            + "format, so compressionQuality only affects encode fidelity here, not resident size. "
                            + "Lower dedupSimilarityThreshold or vramBudgetTargetMb expectations, or accept the overage.",
                    width, height, projectedBytes / (1024 * 1024), budgetBytes / (1024 * 1024)
            );
        }

        return preset;
    }

    public record CompressionResult(ByteBuffer compressedBlocks, int qualityPresetUsed, boolean fromCache) {
    }
}
