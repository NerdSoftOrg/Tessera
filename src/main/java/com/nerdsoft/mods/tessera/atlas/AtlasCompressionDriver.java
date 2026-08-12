package com.nerdsoft.mods.tessera.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nerdsoft.mods.tessera.cache.AtlasCache;
import com.nerdsoft.mods.tessera.compress.*;
import com.nerdsoft.mods.tessera.config.Config;
import com.nerdsoft.mods.tessera.gui.DebugOverlay;
import com.nerdsoft.mods.tessera.jni.NativeLibraryLoader;
import com.nerdsoft.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.opengl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Generic BC1/BC7 compression + upload driver, extracted from the old
 * {@code SpriteLoaderMixin}'s per-bucket compression path (now disabled --
 * see {@code tessera.mixins.json}) so it can be reused against
 * {@link SplitAtlasManager}'s two independent
 * {@code TextureAtlas} instances without depending on the retired
 * {@code SpriteBucket}/{@code SpriteAtlasRouting} bucket-hack types.
 *
 * <p>Everything here is atlas-identity-agnostic: callers pass a GL texture
 * ID, a target {@link CompressionPipeline.Target}, and the sprite/pixel
 * data to compress. Nothing in this class assumes there is only one atlas,
 * or that atlases share GL texture storage -- both assumptions the old
 * bucket hack made and which caused the original UV-corruption bug.
 *
 * <h2>Two-phase compress/upload split</h2>
 * {@link #compress} (CPU-only, no GL calls, safe on a background executor)
 * and {@link #upload} (GL calls only, render-thread only) are deliberately
 * separate methods. An earlier version of this class fused both into one
 * {@code compressAndUpload} call that ran entirely on the render thread --
 * that meant every resource reload stalled a frame for however long native
 * BC1/BC7 encoding of a full atlas took, since the CPU-bound encoding work
 * had no reason to be on the render thread at all. Callers should run
 * {@link #compress} on the same background executor
 * {@code SpriteLoader.stitch()} was already given, and only call
 * {@link #upload} once back on the render thread (see
 * {@link SplitAtlasManager#beginSplitStitch}/
 * {@link SplitAtlasManager#applyPendingSplitStitch}).
 */
@SuppressWarnings("JavadocReference")
public final class AtlasCompressionDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/AtlasCompressionDriver");

    private static volatile AtlasCache cacheInstance;

    private AtlasCompressionDriver() {
    }

    /**
     * Assembles one contiguous RGBA8 buffer for an entire atlas from its
     * stitched sprites' own pixel data, positioned per each sprite's
     * stitch-time placement ({@code TextureAtlasSprite.getX()/getY()}
     * within the atlas), then runs perceptual near-duplicate detection
     * over the assembled buffer via {@code NativeFamilyDetector} -- lifted
     * unmodified from the old bucket-buffer assembly, since dedup detection
     * is orthogonal to which physical atlas the sprites end up on.
     *
     * @param label       for logging only (e.g. {@code "OPAQUE"}/{@code "ALPHA"})
     * @param regions     every sprite placed in this atlas by its own stitch pass
     * @param atlasWidth  the atlas's own packed width (from {@code Preparations.width()})
     * @param atlasHeight the atlas's own packed height (from {@code Preparations.height()})
     * @return the assembled RGBA8 buffer, or {@code null} if native dedup
     * detection is unavailable and the caller should fall back to
     * an uncompressed upload for this atlas
     */
    @SuppressWarnings("resource")
    public static ByteBuffer assembleAtlasBuffer(
            String label, Collection<TextureAtlasSprite> regions, int atlasWidth, int atlasHeight
    ) {

        // Guard mirrors compress(): don't cross the JNI boundary, and don't pay for the
        // pixel-buffer allocation/copy below, when the bridge never loaded (or is disabled).
        // Without this check, NativeFamilyDetector.detect() calls straight into
        // NativeBridge.detectFamiliesAndAssemble and throws UnsatisfiedLinkError instead
        // of the clean vanilla-atlas fallback this method's own docstring promises.
        if (Config.DISABLE_NATIVE_COMPRESSION.get() || !NativeLibraryLoader.isAvailable()) {
            return null;
        }

        long totalPixelBytes = 0L;
        int[] widths = new int[regions.size()];
        int[] heights = new int[regions.size()];
        int[] srcOffsets = new int[regions.size()];
        int[] destX = new int[regions.size()];
        int[] destY = new int[regions.size()];

        int index = 0;
        for (TextureAtlasSprite sprite : regions) {
            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();
            widths[index] = spriteWidth;
            heights[index] = spriteHeight;
            srcOffsets[index] = (int) totalPixelBytes;
            destX[index] = sprite.getX();
            destY[index] = sprite.getY();
            totalPixelBytes += (long) spriteWidth * spriteHeight * 4;
            index++;
        }

        ByteBuffer pixels = ByteBuffer.allocateDirect((int) totalPixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        List<NativeFamilyDetector.SpriteInput> spriteInputs = new ArrayList<>(regions.size());

        index = 0;
        for (TextureAtlasSprite sprite : regions) {
            int spriteWidth = widths[index];
            int spriteHeight = heights[index];

            pixels.position(srcOffsets[index]);
            for (int y = 0; y < spriteHeight; y++) {
                for (int x = 0; x < spriteWidth; x++) {
                    pixels.putInt(sprite.getPixelRGBA(0, x, y));
                }
            }

            spriteInputs.add(new NativeFamilyDetector.SpriteInput(
                    srcOffsets[index], spriteWidth, spriteHeight, destX[index], destY[index], false));
            index++;
        }
        pixels.rewind();

        NativeFamilyDetector.DetectionResult result = NativeFamilyDetector.detect(
                pixels, spriteInputs, atlasWidth, atlasHeight, Config.DEDUP_SIMILARITY_THRESHOLD.get());
        if (result == null) {
            return null;
        }

        int duplicateCount = result.families().size() < spriteInputs.size()
                ? spriteInputs.size() - result.families().size()
                : 0;
        if (duplicateCount > 0) {
            LOGGER.info("{} of {} sprites in the {} atlas are perceptual near-duplicates of another sprite in this atlas.",
                    duplicateCount, spriteInputs.size(), label);
        }

        return result.atlasBuffer();
    }

    /**
     * Upper-bound byte estimate for an atlas's <em>full</em> mip chain
     * (base level + every level down to 1x1) -- see the original
     * derivation in the retired {@code SpriteLoaderMixin}: a full chain
     * never exceeds 4/3 of the base level's size, since the geometric
     * series {@code sum(1/4^i)} converges to {@code 4/3}.
     */
    public static long estimateFullMipChainBytes(int width, int height, CompressionPipeline.Target target) {
        int alignedWidth = (width + 3) & ~3;
        int alignedHeight = (height + 3) & ~3;
        long baseLevelBytes = (long) (alignedWidth / 4) * (alignedHeight / 4) * target.bytesPerBlock();
        return (baseLevelBytes * 4) / 3;
    }

    /**
     * CPU-only compression phase: builds the mip chain and compresses each
     * level via the native BC1/BC7 encoder. Contains no GL calls and is
     * safe to run on any thread, including a background executor -- this
     * is the phase that previously ran fused into
     * {@code compressAndUpload} directly on the render thread, stalling a
     * frame on every resource reload for however long native encoding of
     * a full atlas took. Callers should run this on a background executor
     * (e.g. the same one {@code SpriteLoader.stitch()} was already given)
     * and only hand the result to {@link #upload} once back on the render
     * thread.
     *
     * <p><strong>Exception: BC1 with GPU compute available.</strong> When
     * {@code target == Target.BC1} and {@link Bc1ComputeSupport#isSupported()},
     * callers should route through {@link #compressBc1OnRenderThread}
     * instead -- GPU compute dispatch requires GL context access and
     * cannot run on a background executor the way this method's CPU path
     * can. This method still handles the BC7 case (always CPU) and the
     * BC1-without-compute-support fallback identically; only orchestration
     * at the caller level (see {@code TesseraSplitAtlasManager}) needs to
     * branch on which path a given atlas should take. This method cannot
     * make that branching decision itself since it has no way to know,
     * from here, whether the caller is currently on the render thread or
     * a background thread.
     *
     * @return a {@link CompressedAtlas} with zero or more levels (zero
     * levels means compression was entirely unavailable/skipped;
     * the caller should fall back to leaving the atlas on its
     * existing uncompressed upload)
     */
    @SuppressWarnings("LoggingSimilarMessage")
    public static CompressedAtlas compress(
            ResourceLocation atlasLocation, CompressionPipeline.Target target,
            ByteBuffer baseRgba8, int baseWidth, int baseHeight, int requestedMaxLevel
    ) {
        if (target == CompressionPipeline.Target.BC7 && !Bc7GpuSupport.isSupported()) {
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (BC7 unsupported on this GPU).", atlasLocation);
            return new CompressedAtlas(atlasLocation, target, List.of(), requestedMaxLevel);
        }

        long fullChainEstimateBytes = estimateFullMipChainBytes(baseWidth, baseHeight, target);
        if (!VramBudgetEngine.isWithinBudget(fullChainEstimateBytes, 0)) {
            LOGGER.warn("Atlas {} (full mip chain estimate) exceeds VRAM budget target. Falling back to uncompressed RGBA for this atlas.",
                    atlasLocation);
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (exceeds VRAM budget).", atlasLocation);
            return new CompressedAtlas(atlasLocation, target, List.of(), requestedMaxLevel);
        }

        List<MipChainBuilder.MipLevel> mipLevels = MipChainBuilder.build(baseRgba8, baseWidth, baseHeight, requestedMaxLevel);
        if (mipLevels == null || mipLevels.isEmpty()) {
            // Native mip-chain generation unavailable/failed entirely --
            // fall back to compressing level 0 only.
            mipLevels = List.of(new MipChainBuilder.MipLevel(baseWidth, baseHeight, baseRgba8));
        }

        CompressionPipeline pipeline = new CompressionPipeline(cache());
        List<CompressedLevel> compressedLevels = new ArrayList<>(mipLevels.size());

        for (int level = 0; level < mipLevels.size(); level++) {
            MipChainBuilder.MipLevel mipLevel = mipLevels.get(level);
            Optional<CompressionPipeline.CompressionResult> result =
                    pipeline.compress(mipLevel.rgba8(), mipLevel.width(), mipLevel.height(), target);
            if (result.isEmpty()) {
                LOGGER.info("[Tessera coverage] Atlas {} mip level {} SKIPPED (compression unavailable); stopping chain here.",
                        atlasLocation, level);
                break;
            }
            compressedLevels.add(new CompressedLevel(level, mipLevel.width(), mipLevel.height(), result.get().compressedBlocks()));
        }

        return new CompressedAtlas(atlasLocation, target, compressedLevels, requestedMaxLevel);
    }

    /**
     * Render-thread BC1 compression via {@link Bc1ComputeEncoder}, for use
     * when {@link Bc1ComputeSupport#isSupported()} -- this is the GPU
     * counterpart to {@link #compress}'s CPU path, producing the same
     * {@link CompressedAtlas} shape so {@link #upload} works identically
     * regardless of which path produced it.
     *
     * <p><strong>Current limitation:</strong> only the base level (level
     * 0) is GPU-encoded. The remaining mip levels still go through
     * {@link MipChainBuilder}'s CPU box-filter downsampling followed by
     * {@link Bc1ComputeEncoder#encode} per level -- this method does loop
     * over all requested levels via that combination, so a full mip chain
     * is still produced, but the downsampling step itself (not the BC1
     * encoding) remains CPU work run inline on the render thread here,
     * which reintroduces a smaller version of the original stall for
     * atlases with a deep requested mip chain. Moving mip downsampling to
     * either a background thread (before this method is called) or a
     * second compute shader is the natural next optimization but is not
     * implemented in this pass -- flagging rather than silently accepting
     * a partial regression of the earlier CPU-off-render-thread fix.
     *
     * @return a {@link CompressedAtlas}, or one with zero levels if GPU
     * encoding failed for the base level (caller should fall back
     * to {@link #compress}'s CPU path entirely in that case)
     */
    public static CompressedAtlas compressBc1OnRenderThread(
            ResourceLocation atlasLocation, ByteBuffer baseRgba8, int baseWidth, int baseHeight, int requestedMaxLevel
    ) {
        List<MipChainBuilder.MipLevel> mipLevels = MipChainBuilder.build(baseRgba8, baseWidth, baseHeight, requestedMaxLevel);
        if (mipLevels == null || mipLevels.isEmpty()) {
            mipLevels = List.of(new MipChainBuilder.MipLevel(baseWidth, baseHeight, baseRgba8));
        }

        List<CompressedLevel> compressedLevels = new ArrayList<>(mipLevels.size());
        for (int level = 0; level < mipLevels.size(); level++) {
            MipChainBuilder.MipLevel mipLevel = mipLevels.get(level);
            Optional<Bc1ComputeEncoder.EncodedBlocks> encoded =
                    Bc1ComputeEncoder.encode(mipLevel.rgba8(), mipLevel.width(), mipLevel.height());
            if (encoded.isEmpty()) {
                LOGGER.info("[Tessera coverage] Atlas {} GPU BC1 encode failed at mip level {}; stopping chain here.",
                        atlasLocation, level);
                break;
            }
            compressedLevels.add(new CompressedLevel(level, mipLevel.width(), mipLevel.height(), encoded.get().packedBlocks()));
        }

        return new CompressedAtlas(atlasLocation, CompressionPipeline.Target.BC1, compressedLevels, requestedMaxLevel);
    }

    /**
     * Render-thread-only upload phase: takes a {@link CompressedAtlas}
     * already built by {@link #compress} (on a background thread) and
     * issues the actual {@code glCompressedTexImage2D} calls. This is the
     * only part of the pipeline that touches GL and therefore the only
     * part that must run on the render thread.
     *
     * @return total resident bytes across all uploaded levels, or
     * {@code -1} if not even the base level could be uploaded (or
     * {@code compressed} had zero levels to begin with) -- the
     * caller should leave the atlas on its existing uncompressed
     * upload in that case
     */
    public static long upload(int textureId, CompressedAtlas compressed) {
        if (compressed.levels().isEmpty()) {
            return -1;
        }

        // Per-atlas GL debug group (added because debug.log kept showing
        // GL_INVALID_OPERATION as "in (null)" with no attributable call
        // site, making BC1's opaque-atlas errors indistinguishable from
        // BC7's alpha-atlas errors in the log). Every GL call between
        // push/pop below -- including any async debug messages the driver
        // emits for them -- is now tagged with this atlas's own location
        // string. GL_DEBUG_SOURCE_APPLICATION is the correct source
        // constant for a marker inserted by application/mod code (as
        // opposed to GL_DEBUG_SOURCE_API, reserved for the driver's own
        // generated messages). NVIDIA/AMD both echo
        // the group string back in subsequent messages until the matching
        // pop; Intel's Windows driver (this project's actual target
        // hardware per debug.log's "Intel(R) Iris(R) Xe Graphics") is
        // known to be inconsistent about honoring this on some builds --
        // UNVERIFIED whether it does on the specific driver version in
        // that log. Pushing the group is harmless when unsupported/
        // ignored (falls back to exactly today's "in (null)" messages,
        // no regression) and costs nothing when it is honored, so there
        // is no downside to doing it unconditionally.
        String debugGroupLabel = "tessera:upload:" + compressed.atlasLocation();
        GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0, debugGroupLabel);
        try {
            return tessera$uploadInner(textureId, compressed);
        } finally {
            GL43.glPopDebugGroup();

            // One-shot, non-looping poll -- deliberately NOT the flood-
            // amplifying spin loop the removed tessera$flushGlErrors() was
            // (see uploadCompressedLevel's own doc comment on why that
            // was wrong). A single call here produces at most one log
            // line per upload() invocation (there are only ever a
            // handful of these per reload, one per atlas), so this cannot
            // recreate the multi-thousand-line flood the way polling
            // inside a per-sprite or per-block loop would. This does NOT
            // replace the async GLDebugMessageCallback as the primary
            // error-reporting mechanism -- that is untouched and is what
            // actually identifies *which* GL call failed; this is purely
            // a "did upload() as a whole leave any error flagged" signal,
            // logged at DEBUG so it stays out of the way by default.
            int trailingError = GL11.glGetError();
            if (trailingError != GL11.GL_NO_ERROR) {
                LOGGER.debug("GL error {} flagged at some point during {} (see the async GlDebug messages logged around this atlas's upload for the specific failing call).",
                        trailingError, debugGroupLabel);
            }
        }
    }

    private static long tessera$uploadInner(int textureId, CompressedAtlas compressed) {
        RenderSystem.bindTexture(textureId);

        // requestedMaxLevel is the same maxMipLevel vanilla's own
        // SpriteLoader.stitch() call was given for this atlas (see
        // TesseraSplitAtlasManager#tessera$stitchSplit) -- clamped here
        // defensively so compress()'s caller can never walk this driver
        // past a level index deeper than what was actually requested,
        // regardless of how many levels MipChainBuilder produced. Note
        // this does NOT guard against vanilla's allocated storage depth,
        // since glCompressedTexImage2D (see uploadCompressedLevel) respecifies
        // each level's storage itself rather than writing into a
        // pre-sized allocation -- there is no fixed "allocated levels"
        // ceiling to violate on mutable-storage textures the way there
        // would be on an immutable one.
        int maxAllocatedLevel = compressed.requestedMaxLevel();

        long totalResidentBytes = 0L;
        // Sum of each uploaded level's own uncompressed RGBA8 footprint
        // (width*height*4), NOT just the base level -- a full mip chain's
        // uncompressed baseline is the same geometric-series sum used by
        // estimateFullMipChainBytes, so tallying per-level here rather
        // than assuming level 0 alone keeps the DebugOverlay's "saved MB"
        // figure consistent with what vanilla's own uncompressed upload
        // would actually have resident for the same level count.
        long totalUncompressedBytes = 0L;
        int lastUploadedLevel = -1;

        for (CompressedLevel level : compressed.levels()) {
            if (level.level() > maxAllocatedLevel) {
                LOGGER.warn("Atlas {} compressed chain produced level {} beyond the {} level(s) vanilla's own upload allocated storage for; stopping chain here.",
                        compressed.atlasLocation(), level.level(), maxAllocatedLevel + 1);
                break;
            }
            boolean uploaded = uploadCompressedLevel(
                    compressed.atlasLocation(), compressed.target(), level.level(), level.width(), level.height(), level.compressedBlocks());
            if (!uploaded) {
                break;
            }
            totalResidentBytes += level.compressedBlocks().remaining();
            totalUncompressedBytes += (long) level.width() * level.height() * 4;
            lastUploadedLevel = level.level();
        }

        if (lastUploadedLevel < 0) {
            return -1;
        }

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, lastUploadedLevel);
        applyTextureFilteringState(lastUploadedLevel);

        if (lastUploadedLevel < compressed.requestedMaxLevel()) {
            LOGGER.info("Atlas {} mip chain stopped early at level {} of {} requested.",
                    compressed.atlasLocation(), lastUploadedLevel, compressed.requestedMaxLevel());
        }

        // Feeds the F3 debug overlay (see DebugOverlay#recordCompression).
        // This call site did not exist before this fix: the overlay's
        // isCompressedAtlasActive flag and byte counters had zero
        // producers anywhere in the codebase (recordCompression/
        // recordBucketCompression were dead code, leftover from the
        // retired SpriteLoaderMixin -- see that method's own stale
        // doc-comment reference in Tessera#onAtlasStitched), so the
        // overlay printed "Compression: DISABLED" unconditionally on
        // every frame regardless of whether compression actually
        // succeeded. upload() succeeding past this point is exactly the
        // "compression is active and resident" signal the overlay needs.
        long savedBytes = totalUncompressedBytes - totalResidentBytes;
        DebugOverlay.recordCompression(compressed.atlasLocation().toString(), savedBytes, totalResidentBytes);
        DebugOverlay.recordBucketCompression(
                compressed.atlasLocation().toString(), compressed.target().name(), savedBytes, totalResidentBytes);

        return totalResidentBytes;
    }

    private static void applyTextureFilteringState(int maxMipLevel) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                maxMipLevel > 0 ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private static boolean uploadCompressedLevel(
            ResourceLocation atlasLocation, CompressionPipeline.Target target,
            int level, int width, int height, ByteBuffer compressedBlocks
    ) {
        int alignedWidth = (width + 3) & ~3;
        int alignedHeight = (height + 3) & ~3;
        int expectedBytes = (alignedWidth / 4) * (alignedHeight / 4) * target.bytesPerBlock();

        if (compressedBlocks.remaining() != expectedBytes) {
            LOGGER.error(
                    "{} size mismatch for atlas {} mip level {} ({}x{}): received {} bytes, expected {}.",
                    target, atlasLocation, level, width, height, compressedBlocks.remaining(), expectedBytes
            );
            return false;
        }

        // Last-line defense, independent of which upstream path produced
        // these blocks (CompressionPipeline#compress on the CPU side,
        // Bc1ComputeEncoder#encode on the GPU-compute side, or any future
        // caller): never issue glCompressedTexImage2D with an S3TC enum
        // the driver hasn't advertised. This is what was previously
        // missing -- BC1ComputeSupport/isBc1NativeAvailable both gate on
        // unrelated capabilities, so this call could still fire on a
        // driver without GL_EXT_texture_compression_s3tc and leave the
        // texture's storage for this level undefined (GL_INVALID_OPERATION,
        // then intermittently-invisible block textures depending on how
        // the driver happens to handle the resulting incomplete image).
        if (target == CompressionPipeline.Target.BC1 && !Bc1TextureFormatSupport.isSupported()) {
            LOGGER.warn("Atlas {} BC1 upload skipped: driver does not advertise GL_EXT_texture_compression_s3tc.",
                    atlasLocation);
            return false;
        }

        int glInternalFormat = target == CompressionPipeline.Target.BC1
                ? EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT
                : GL42.GL_COMPRESSED_RGBA_BPTC_UNORM;

        // Root-cause fix (debug.log: GL_INVALID_OPERATION id=1282 firing
        // repeatedly right after "Created: WxHx0 tessera:atlas/*-atlas").
        // Vanilla's TextureAtlas.upload(Preparations) allocates this
        // texture's storage via glTexImage2D per level -- MUTABLE storage,
        // format GL_RGBA8 -- not glTexStorage2D. (TextureAtlas has never
        // opted into ARB_texture_storage; only explicit immutable-storage
        // call sites, e.g. some Sodium/Iris-side textures, use that path.)
        // glCompressedTexSubImage2D requires the target level's EXISTING
        // internal format to already be a compressed format matching the
        // call -- writing compressed blocks into a level GL still considers
        // RGBA8 is GL_INVALID_OPERATION regardless of dimensions matching.
        // glCompressedTexImage2D is therefore correct here: it respecifies
        // the level's format+storage in one call, which is legal (and
        // required) against mutable storage. This was previously believed
        // to be the bug (see git history), but vanilla's atlas storage was
        // never immutable, so that theory does not hold; reverting to
        // glCompressedTexImage2D is the actual fix.
        GL13.glCompressedTexImage2D(
                GL11.GL_TEXTURE_2D, level, glInternalFormat, alignedWidth, alignedHeight, 0, compressedBlocks
        );

        // No glGetError() poll here by design (the removed
        // tessera$flushGlErrors() spin loop was one). Polling error state
        // in a loop on the render thread is a synchronous pipeline stall
        // and, on this driver (Intel Iris Xe, Windows), each real
        // GL_INVALID_OPERATION was additionally being re-emitted by
        // Mojang's own GLDebugMessageCallback (installed by GlDebug) on
        // every subsequent poll until the queue drained -- that interaction
        // is what turned a handful of real errors into the multi-thousand-
        // line flood in debug.log. The async debug callback is the correct,
        // already-installed error-reporting mechanism; this call site does
        // not need to (and must not) poll glGetError() itself. Level-upload
        // failure is now only detectable via the debug callback's own log
        // output, not a return value -- this method reports success
        // unconditionally past the size-mismatch guard above, same as
        // every other GL call in this codebase that isn't manually
        // wrapped in a glGetError() check.

        long uncompressedSize = (long) width * height * 4;
        long compressedSize = compressedBlocks.remaining();
        double savedMB = (uncompressedSize - compressedSize) / (1024.0 * 1024.0);
        LOGGER.info("Successfully compressed atlas {} to {} mip level {}: {}x{}. VRAM saved: {} MB",
                atlasLocation, target, level, width, height, String.format("%.2f", savedMB));
        return true;
    }

    private static synchronized AtlasCache cache() {
        if (cacheInstance == null) {
            cacheInstance = new AtlasCache(FMLPaths.GAMEDIR.get().resolve(Config.CACHE_DIRECTORY.get()));
        }
        return cacheInstance;
    }

    /**
     * Drops the memoized {@link AtlasCache}, forcing the next {@link #cache()}
     * call to re-read {@link Config#CACHE_DIRECTORY} and rebuild it against
     * the current value. Without this, {@code cacheInstance} stays pinned to
     * whatever directory was configured the first time any reload needed the
     * cache, for the rest of the game session -- changing
     * {@code cacheDirectory} in the settings screen would silently do
     * nothing until restart. Called from {@code Tessera}'s config-reload
     * listener.
     */
    public static synchronized void invalidateCache() {
        cacheInstance = null;
    }

    /**
     * Result of the CPU-side compression phase: every successfully
     * compressed mip level, ready to be uploaded. Carries no GL state and
     * touches no GL calls to produce -- safe to build entirely on a
     * background executor thread. {@link #upload} is the only part of
     * this pipeline that must run on the render thread.
     */
    public record CompressedAtlas(
            ResourceLocation atlasLocation,
            CompressionPipeline.Target target,
            List<CompressedLevel> levels,
            int requestedMaxLevel
    ) {
    }

    public record CompressedLevel(int level, int width, int height, ByteBuffer compressedBlocks) {
    }
}