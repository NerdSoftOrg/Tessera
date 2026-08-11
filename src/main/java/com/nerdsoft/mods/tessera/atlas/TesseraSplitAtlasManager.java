package com.nerdsoft.mods.tessera.atlas;

import com.nerdsoft.mods.tessera.compress.Bc1ComputeSupport;
import com.nerdsoft.mods.tessera.compress.CompressionPipeline;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Owns the two independent static atlases ({@link AtlasSplitTarget#OPAQUE},
 * {@link AtlasSplitTarget#ALPHA}) as real, standalone {@link TextureAtlas}
 * instances -- each with its own GL texture ID and its own
 * {@code SpriteLoader.stitch()} pass, so every {@code TextureAtlasSprite}
 * they produce has UVs correctly relative to <em>that</em> atlas alone.
 *
 * <h2>Why this replaces the old bucket hack</h2>
 * The previous design routed three logical "buckets" through one shared
 * {@code TextureAtlas} object and three GL texture IDs underneath it. That
 * broke because every consumer of a {@code TextureAtlasSprite} -- block
 * models, GUI blit calls, particle rendering -- assumes exactly one
 * physical texture backs the one atlas it was stitched into. Multiplexing
 * three physical textures behind one logical atlas identity meant UVs baked
 * for one bucket's packing got sampled against a different bucket's (or
 * vanilla's own) bound texture whenever a draw call skipped Tessera's
 * routing table, which is most of them.
 *
 * <p>This class fixes that at the root: {@link #tessera$opaqueAtlas} and
 * {@link #tessera$alphaAtlas} are ordinary, independent atlases as far as
 * vanilla's {@code SpriteLoader}/{@code Stitcher} are concerned. Nothing
 * downstream needs a routing table to find the right UV space -- the UVs
 * are already correct because each atlas was stitched on its own. What
 * downstream code (block/chunk render routing) still needs is to know
 * <em>which atlas</em> a given sprite ended up on, which is what
 * {@link #routingFor} answers.
 *
 * <h2>Compression</h2>
 * BC1/BC7 compression itself runs on the background executor, immediately
 * after each atlas's own stitch completes (see {@link #tessera$stitchSplit}/
 * {@link #tessera$compressInBackground}) -- not on the render thread. Only
 * the final GL upload of the compressed result happens on the render
 * thread (see {@link #tessera$applyOnRenderThread}), via
 * {@link AtlasCompressionDriver#upload}, immediately after vanilla's own
 * uncompressed upload for that atlas. This two-phase split exists because
 * an earlier version of this class ran compression fused into the
 * render-thread upload call, stalling a frame on every resource reload for
 * however long native BC1/BC7 encoding of a full atlas took. Compression
 * failure of any kind (unsupported GPU, VRAM budget, native bridge
 * unavailable) leaves an atlas on vanilla's already-completed uncompressed
 * upload rather than leaving it without texture data.
 *
 * <h2>Registration and reload sequencing</h2>
 * Registered as a {@link PreparableReloadListener} via
 * {@code RegisterClientReloadListenersEvent} purely so its two
 * {@link TextureAtlas} instances participate in the client resource
 * manager's listener lifecycle (see {@link #reload} for why that method
 * itself is a no-op). The actual stitch/upload work does not run on an
 * independent reload cycle -- it rides on vanilla's own
 * {@code SpriteLoader.stitch()} call for the source atlas
 * ({@code minecraft:textures/atlas/blocks}), driven by
 * {@code SpriteRoutingMixin} calling {@link #beginSplitStitch} /
 * {@link #applyPendingSplitStitch} at the matching points in that call's
 * own prepare/apply phases. This is deliberate: vanilla's {@code Stitcher}
 * is the only place the full static sprite list for that atlas is
 * available (see the mixin's own doc comment), so Tessera reuses that
 * exact list rather than re-discovering it via a second, independent
 * reload pass that would need its own model-material enumeration.
 */
public final class TesseraSplitAtlasManager implements PreparableReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SplitAtlasManager");

    private final TextureAtlas tessera$opaqueAtlas = new TextureAtlas(AtlasSplitTarget.OPAQUE.atlasLocation());
    private final TextureAtlas tessera$alphaAtlas = new TextureAtlas(AtlasSplitTarget.ALPHA.atlasLocation());

    /**
     * Per-reload routing: which {@link AtlasSplitTarget} a given sprite
     * {@link ResourceLocation} ended up on. Published once per successful
     * reload (see {@link #tessera$applyOnRenderThread}), consumed by the
     * block/chunk render-type routing mixin that reads which atlas each
     * sprite's quads should bind against. Volatile rather than
     * synchronized -- published via a single reference swap after the
     * reload's apply phase completes on the render thread, read from the
     * render thread thereafter; no concurrent-mutation window exists once
     * published.
     */
    private volatile Map<ResourceLocation, AtlasSplitTarget> tessera$spriteRouting = Map.of();
    private volatile StitchResult tessera$pendingResult;

    public TextureAtlas atlasFor(AtlasSplitTarget target) {
        return target == AtlasSplitTarget.OPAQUE ? tessera$opaqueAtlas : tessera$alphaAtlas;
    }

    /**
     * Returns which atlas a sprite was routed to, or {@code null} if this
     * sprite was not part of the static split for the current reload (e.g.
     * it is a dynamic/ticked sprite left on vanilla's own atlas, or it
     * belongs to an atlas Tessera does not split, such as GUI or particles
     * in this pass).
     */
    public AtlasSplitTarget routingFor(ResourceLocation spriteName) {
        return tessera$spriteRouting.get(spriteName);
    }

    @Override
    public @NotNull CompletableFuture<Void> reload(
            PreparationBarrier barrier, @NotNull ResourceManager resourceManager,
            @NotNull ProfilerFiller prepareProfiler, @NotNull ProfilerFiller applyProfiler,
            @NotNull Executor backgroundExecutor, @NotNull Executor gameExecutor
    ) {
        // Intentionally a no-op reload of its own. Tessera's two split
        // atlases do not run an independent reload cycle -- they ride on
        // vanilla's own SpriteLoader.stitch() reload for the source atlas
        // (minecraft:textures/atlas/blocks), via SpriteRoutingMixin calling
        // beginSplitStitch/applyPendingSplitStitch at the right points in
        // that cycle. This class is still registered as a
        // PreparableReloadListener (see Tessera#onRegisterReloadListeners)
        // purely so its two TextureAtlas instances participate in the
        // client resource manager's listener list for lifecycle purposes
        // (e.g. so nothing else assumes it's safe to release GL resources
        // during a reload window before Tessera's own upload has run) --
        // the actual stitch/upload work happens off this method entirely.
        return barrier.wait(null);
    }

    /**
     * Kicks off the split stitch for a batch of static sprites that
     * {@code SpriteRoutingMixin} pulled out of vanilla's own
     * {@code SpriteLoader.stitch()} call for {@code minecraft:textures/atlas/blocks}
     * (or any other atlas Tessera is configured to split -- see
     * {@code TesseraRulesManager.BLACKLISTED_ATLASES}). The mixin already
     * has the exact sprite list vanilla would have stitched (via vanilla's
     * own {@code Stitcher} source-discovery), so Tessera reuses that list
     * directly instead of re-implementing model-material enumeration
     * itself.
     *
     * @param allStaticSprites every non-dynamic sprite vanilla would have
     *                         stitched into the source atlas this reload
     * @param executor         the same background executor vanilla's own
     *                         stitch call was given, so Tessera's two extra
     *                         stitch passes run alongside it rather than
     *                         serially blocking on it
     */
    public CompletableFuture<Void> beginSplitStitch(List<SpriteContents> allStaticSprites, Executor executor) {
        return tessera$stitchSplit(allStaticSprites, executor)
                .thenAccept(result -> tessera$pendingResult = result);
    }

    /**
     * Applies whatever the most recent {@link #beginSplitStitch} produced.
     * Must run on the render thread, after the caller's own
     * {@code PreparationBarrier} has passed -- {@code SpriteRoutingMixin}
     * is responsible for sequencing this the same way vanilla sequences its
     * own reload's apply phase, since Tessera's two extra atlases are
     * riding along on the same reload cycle rather than running their own
     * independent {@link #reload}.
     */
    public void applyPendingSplitStitch(ProfilerFiller profiler) {
        StitchResult result = tessera$pendingResult;
        if (result == null) {
            return;
        }
        tessera$pendingResult = null;
        tessera$applyOnRenderThread(result, profiler);
    }

    /**
     * Classifies the full static sprite set via {@link SpriteClassifier}
     * (reused unmodified from the old design -- its opaque/punch-through/
     * blended alpha-shape detection is correct and orthogonal to the
     * atlas-identity bug this rewrite fixes), then runs two fully
     * independent {@code SpriteLoader.stitch()} calls, one per target
     * atlas. Each call only ever sees sprites destined for that one atlas,
     * so the {@code Stitcher}'s bin-packing and UV assignment for each
     * atlas is self-contained by construction -- there is no merge step
     * that could reintroduce cross-atlas coordinate confusion.
     *
     * <p>CPU-side BC7 compression ({@link AtlasCompressionDriver#compress})
     * happens here, on {@code executor}, immediately after the alpha
     * atlas's stitch completes -- this is the fix from the previous
     * design, where compression ran fused into the render-thread upload
     * call and stalled a frame on every reload.
     *
     * <p>The opaque atlas's BC1 compression is conditional:
     * when {@link Bc1ComputeSupport#isSupported()}, only pixel-buffer
     * <em>assembly</em> happens here in the background (still CPU work,
     * still safe off the render thread) -- the actual BC1 encoding is
     * deferred to {@link #tessera$applyOnRenderThread} via
     * {@link AtlasCompressionDriver#compressBc1OnRenderThread}, since GPU
     * compute dispatch requires render-thread GL context access and
     * cannot run here. When GPU compute is unavailable, the opaque atlas
     * falls back to the same background CPU compression path as the alpha
     * atlas.
     */
    private CompletableFuture<StitchResult> tessera$stitchSplit(List<SpriteContents> allStaticSprites, Executor executor) {
        SpriteClassifier.ClassificationResult classification = SpriteClassifier.classify(allStaticSprites);

        List<SpriteContents> opaqueSprites = tessera$mergeOpaqueBuckets(classification);
        List<SpriteContents> alphaSprites = classification.bucket(SpriteBucket.ALPHA_BC7);

        // Each split atlas gets its own independent SpriteLoader.stitch() call
        // (see class doc), so each independently needs a sprite satisfying
        // SpriteLoader's own missing() detection (match against
        // MissingTextureAtlasSprite.getLocation()) -- but SpriteClassifier routes
        // every sprite, including the missing-texture one, into exactly ONE
        // bucket by alpha shape. Whichever atlas didn't receive it stitches with
        // missing() == null, and vanilla's TextureAtlas.upload() throws
        // IllegalStateException ("has no missing texture sprite") the moment that
        // atlas has at least one region -- this was reachable any time the
        // classifier's real (non-fallback) path ran and picked one side for the
        // missing sprite. Force it into both lists here rather than relying on
        // classification, since both atlases need it regardless of its own alpha
        // shape. A plain contains() check is fine: allStaticSprites is at most a
        // few thousand SpriteContents per reload, this runs once per reload, and
        // SpriteContents doesn't override equals()/hashCode() so this is an
        // identity check either way -- not worth a Set for one lookup per list.
        SpriteContents missingSprite = null;
        for (SpriteContents contents : allStaticSprites) {
            if (contents.name().equals(MissingTextureAtlasSprite.getLocation())) {
                missingSprite = contents;
                break;
            }
        }
        if (missingSprite != null) {
            if (!opaqueSprites.contains(missingSprite)) {
                opaqueSprites.add(missingSprite);
            }
            if (!alphaSprites.contains(missingSprite)) {
                alphaSprites.add(missingSprite);
            }
        }

        SpriteLoader opaqueLoader = SpriteLoader.create(tessera$opaqueAtlas);
        SpriteLoader alphaLoader = SpriteLoader.create(tessera$alphaAtlas);

        // maxMipLevel is 0 at stitch time deliberately: this only controls
        // vanilla's own SpriteLoader/Stitcher mip generation for the
        // uncompressed baseline upload. Tessera's own compressed mip chain
        // (built below via AtlasCompressionDriver) is independent of
        // stitch-time mip level, so stitching at mip 0 here costs nothing
        // and avoids doing the same downsampling work twice.

        CompletableFuture<SpriteLoader.Preparations> opaqueFuture = opaqueSprites.isEmpty()
                ? CompletableFuture.completedFuture(tessera$emptyPreparations())
                : opaqueLoader.stitch(opaqueSprites, 0, executor).waitForUpload();

        CompletableFuture<SpriteLoader.Preparations> alphaFuture = alphaSprites.isEmpty()
                ? CompletableFuture.completedFuture(tessera$emptyPreparations())
                : alphaLoader.stitch(alphaSprites, 0, executor).waitForUpload();

        // thenApplyAsync(..., executor) rather than thenApply(...) pins
        // this work to the background executor explicitly -- without it,
        // it would run on whichever thread completes opaqueFuture/
        // alphaFuture, which for a stitch() future is normally the
        // background executor but isn't contractually guaranteed to stay
        // that way, so pinning avoids ever risking this sneaking onto the
        // render thread.
        CompletableFuture<OpaqueBackgroundResult> opaqueBackgroundFuture =
                opaqueFuture.thenApplyAsync(this::tessera$prepareOpaqueInBackground, executor);
        CompletableFuture<AtlasCompressionDriver.CompressedAtlas> alphaCompressedFuture =
                alphaFuture.thenApplyAsync(prep -> tessera$compressInBackground(AtlasSplitTarget.ALPHA, prep), executor);

        CompletableFuture<StitchResult> preparationsAndRouting = opaqueFuture.thenCombine(alphaFuture,
                (opaquePrep, alphaPrep) -> new StitchResult(
                        opaquePrep, alphaPrep, tessera$buildRouting(opaquePrep, alphaPrep), null, null, null));

        return preparationsAndRouting.thenCombine(opaqueBackgroundFuture, (partial, opaqueBg) ->
                        new StitchResult(partial.opaquePreparations(), partial.alphaPreparations(), partial.routing(),
                                opaqueBg.compressed(), null, opaqueBg.assembledBuffer()))
                .thenCombine(alphaCompressedFuture, (partial, alphaCompressed) ->
                        new StitchResult(partial.opaquePreparations(), partial.alphaPreparations(), partial.routing(),
                                partial.opaqueCompressed(), alphaCompressed, partial.opaqueAssembledBuffer()));
    }

    /**
     * Background-executor-safe preparation for the opaque atlas. Branches
     * on {@link Bc1ComputeSupport#isSupported()}:
     * <ul>
     *   <li>GPU compute available: only assembles the RGBA8 pixel buffer
     *       (still pure CPU work, safe here) and defers actual BC1
     *       encoding to the render thread -- see
     *       {@link #tessera$applyOnRenderThread}.</li>
     *   <li>GPU compute unavailable: runs the full CPU compression path
     *       via {@link #tessera$compressInBackground}, identical to how
     *       the alpha atlas is always handled.</li>
     * </ul>
     */
    @SuppressWarnings("LoggingSimilarMessage")
    private OpaqueBackgroundResult tessera$prepareOpaqueInBackground(SpriteLoader.Preparations preparations) {
        if (preparations.regions().isEmpty()) {
            return new OpaqueBackgroundResult(
                    new AtlasCompressionDriver.CompressedAtlas(AtlasSplitTarget.OPAQUE.atlasLocation(), CompressionPipeline.Target.BC1, List.of(), 0),
                    null);
        }

        if (Bc1ComputeSupport.isSupported()) {
            ByteBuffer baseRgba8 = AtlasCompressionDriver.assembleAtlasBuffer(
                    AtlasSplitTarget.OPAQUE.name(), preparations.regions().values(), preparations.width(), preparations.height());
            if (baseRgba8 == null) {
                LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (native buffer assembly unavailable); remains uncompressed RGBA8.",
                        AtlasSplitTarget.OPAQUE.atlasLocation());
                return new OpaqueBackgroundResult(
                        new AtlasCompressionDriver.CompressedAtlas(AtlasSplitTarget.OPAQUE.atlasLocation(), CompressionPipeline.Target.BC1, List.of(), preparations.mipLevel()),
                        null);
            }
            // Deferred to the render thread -- see tessera$applyOnRenderThread.
            return new OpaqueBackgroundResult(null, baseRgba8);
        }

        return new OpaqueBackgroundResult(tessera$compressInBackground(AtlasSplitTarget.OPAQUE, preparations), null);
    }

    /**
     * Runs {@link AtlasCompressionDriver#compress} for one atlas. Called
     * from a {@code thenApplyAsync(..., executor)} continuation (see
     * {@link #tessera$stitchSplit}), so this always executes on the
     * background executor, never the render thread. Returns an empty
     * {@code CompressedAtlas} (zero levels) for an empty atlas, which
     * {@link #tessera$applyOnRenderThread} treats as "nothing to upload,
     * leave vanilla's own upload as-is" -- the same fallback behavior the
     * old fused design had.
     */
    private AtlasCompressionDriver.CompressedAtlas tessera$compressInBackground(
            AtlasSplitTarget target, SpriteLoader.Preparations preparations
    ) {
        if (preparations.regions().isEmpty()) {
            return new AtlasCompressionDriver.CompressedAtlas(target.atlasLocation(), target.compressionTarget(), List.of(), 0);
        }

        ByteBuffer baseRgba8 = AtlasCompressionDriver.assembleAtlasBuffer(
                target.name(), preparations.regions().values(), preparations.width(), preparations.height());
        if (baseRgba8 == null) {
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (native buffer assembly unavailable); remains uncompressed RGBA8.",
                    target.atlasLocation());
            return new AtlasCompressionDriver.CompressedAtlas(target.atlasLocation(), target.compressionTarget(), List.of(), preparations.mipLevel());
        }

        return AtlasCompressionDriver.compress(
                target.atlasLocation(), target.compressionTarget(),
                baseRgba8, preparations.width(), preparations.height(), preparations.mipLevel());
    }

    /**
     * {@code AtlasSplitTarget.OPAQUE} covers both {@code SpriteBucket
     * .OPAQUE_BC1} and {@code SpriteBucket.PUNCHTHROUGH_BC1} -- both encode
     * to BC1 at the same 4bpp rate and share one physical atlas, matching
     * the two-atlas requirement (Opaque / Alpha) rather than the old
     * four-way bucket split.
     */
    private List<SpriteContents> tessera$mergeOpaqueBuckets(SpriteClassifier.ClassificationResult classification) {
        List<SpriteContents> merged = new ArrayList<>(
                classification.bucket(SpriteBucket.OPAQUE_BC1).size()
                        + classification.bucket(SpriteBucket.PUNCHTHROUGH_BC1).size());
        merged.addAll(classification.bucket(SpriteBucket.OPAQUE_BC1));
        merged.addAll(classification.bucket(SpriteBucket.PUNCHTHROUGH_BC1));
        return merged;
    }

    private Map<ResourceLocation, AtlasSplitTarget> tessera$buildRouting(SpriteLoader.Preparations opaque, SpriteLoader.Preparations alpha) {
        Map<ResourceLocation, AtlasSplitTarget> map = new HashMap<>();
        opaque.regions().keySet().forEach(
                loc -> map.put(loc, AtlasSplitTarget.OPAQUE));
        alpha.regions().keySet().forEach(
                loc -> map.put(loc, AtlasSplitTarget.ALPHA));
        return map;
    }

    private SpriteLoader.Preparations tessera$emptyPreparations() {
        return new SpriteLoader.Preparations(0, 0, 0, null, Map.of(), CompletableFuture.completedFuture(null));
    }

    /**
     * Render-thread apply phase. Vanilla's own
     * {@code TextureAtlas.upload(Preparations)} runs first for each atlas
     * -- this is what creates the atlas's GL texture ID and performs the
     * baseline RGBA8 upload, exactly as it would for any vanilla atlas.
     *
     * <p>For the alpha atlas, BC7 compression already ran on the
     * background executor (see {@link #tessera$stitchSplit}/
     * {@link #tessera$compressInBackground}), so its path here is GL
     * upload only via {@link AtlasCompressionDriver#upload}.
     *
     * <p>For the opaque atlas, one of two things happened in the
     * background (see {@link #tessera$prepareOpaqueInBackground}): either
     * full CPU compression already completed (GPU compute unavailable --
     * same as alpha's path), or only pixel-buffer assembly completed and
     * {@code result.opaqueAssembledBuffer()} is non-null, in which case
     * the actual BC1 GPU compute encode happens right here via
     * {@link AtlasCompressionDriver#compressBc1OnRenderThread} before
     * uploading. This is the one piece of CPU/GPU-bound work that
     * necessarily still runs on the render thread in the GPU-compute path
     * (dispatching a compute shader requires GL context access) -- see
     * that method's own doc comment for the remaining mip-downsampling
     * caveat this implies.
     */
    private void tessera$applyOnRenderThread(StitchResult result, ProfilerFiller profiler) {
        profiler.push("tessera_split_atlas_upload");
        try {
            // Guard: vanilla's TextureAtlas.upload(Preparations) requires a
            // non-null Preparations#missing() and throws
            // IllegalStateException otherwise (TextureAtlas.upload, line
            // 63 -- "Atlas '<loc>' (0 sprites) has no missing texture
            // sprite"). tessera$emptyPreparations() deliberately passes
            // missing=null for a 0-sprite bucket. An empty opaque or alpha
            // bucket is a legitimate, reachable per-reload outcome (e.g.
            // every static sprite this reload classifying into the other
            // bucket), not a corrupted state, so it must not throw.
            // Skipping upload() for an empty bucket leaves that atlas
            // without a GL texture ID this reload, which is safe: with
            // zero sprites routed to it, nothing will ever sample it (see
            // routingFor/tessera$buildRouting).
            boolean opaqueHasSprites = !result.opaquePreparations().regions().isEmpty();
            boolean alphaHasSprites = !result.alphaPreparations().regions().isEmpty();

            if (opaqueHasSprites) {
                tessera$opaqueAtlas.upload(result.opaquePreparations());
            }
            if (alphaHasSprites) {
                tessera$alphaAtlas.upload(result.alphaPreparations());
            }
            tessera$spriteRouting = result.routing();
            LOGGER.info("Tessera split atlases stitched: {} opaque sprites, {} alpha sprites.",
                    result.opaquePreparations().regions().size(),
                    result.alphaPreparations().regions().size());

            AtlasCompressionDriver.CompressedAtlas opaqueCompressed = result.opaqueCompressed();
            if (opaqueHasSprites && opaqueCompressed == null && result.opaqueAssembledBuffer() != null) {
                opaqueCompressed = AtlasCompressionDriver.compressBc1OnRenderThread(
                        AtlasSplitTarget.OPAQUE.atlasLocation(),
                        result.opaqueAssembledBuffer(),
                        result.opaquePreparations().width(),
                        result.opaquePreparations().height(),
                        result.opaquePreparations().mipLevel());
            }

            // getId() is only well-defined once upload() actually ran for
            // that atlas this reload -- gate on the same *HasSprites flags
            // used above rather than only on opaqueCompressed/
            // alphaCompressed being non-empty, since an empty bucket's own
            // CompressedAtlas (see tessera$compressInBackground /
            // tessera$prepareOpaqueInBackground) is also an empty-levels
            // placeholder that would otherwise read as "upload against a
            // texture ID that was never created this reload."
            long opaqueResident = (opaqueHasSprites && opaqueCompressed != null)
                    ? AtlasCompressionDriver.upload(tessera$opaqueAtlas.getId(), opaqueCompressed)
                    : -1;
            long alphaResident = alphaHasSprites
                    ? AtlasCompressionDriver.upload(tessera$alphaAtlas.getId(), result.alphaCompressed())
                    : -1;

            if (opaqueHasSprites && opaqueResident < 0) {
                LOGGER.info("Atlas {} remains on vanilla's uncompressed RGBA8 upload (compression skipped or failed).",
                        AtlasSplitTarget.OPAQUE.atlasLocation());
            }
            if (alphaHasSprites && alphaResident < 0) {
                LOGGER.info("Atlas {} remains on vanilla's uncompressed RGBA8 upload (compression skipped or failed).",
                        AtlasSplitTarget.ALPHA.atlasLocation());
            }
        } finally {
            profiler.pop();
        }

        this.tessera$spriteRouting = result.routing();
    }

    private record StitchResult(SpriteLoader.Preparations opaquePreparations,
                                SpriteLoader.Preparations alphaPreparations,
                                Map<ResourceLocation, AtlasSplitTarget> routing,
                                AtlasCompressionDriver.CompressedAtlas opaqueCompressed,
                                AtlasCompressionDriver.CompressedAtlas alphaCompressed,
                                ByteBuffer opaqueAssembledBuffer) {
    }

    /**
     * Result of the opaque atlas's background-phase work, which branches
     * depending on GPU BC1 availability -- see {@link #tessera$prepareOpaqueInBackground}.
     * Exactly one of {@code compressed} (non-empty) or {@code assembledBuffer}
     * (non-null) is meaningful for a given reload; the other is the
     * "nothing to do here, the other path was taken" placeholder.
     */
    private record OpaqueBackgroundResult(AtlasCompressionDriver.CompressedAtlas compressed,
                                          ByteBuffer assembledBuffer) {
    }
}