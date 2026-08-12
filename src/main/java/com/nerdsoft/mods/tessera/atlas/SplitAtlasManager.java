package com.nerdsoft.mods.tessera.atlas;

import com.nerdsoft.mods.tessera.compress.Bc1ComputeSupport;
import com.nerdsoft.mods.tessera.compress.Bc1TextureFormatSupport;
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
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
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
@SuppressWarnings("JavadocReference")
public final class SplitAtlasManager implements PreparableReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SplitAtlasManager");
    private static final ThreadLocal<Boolean> IS_DISPATCHING = ThreadLocal.withInitial(() -> false);

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

    // Root-cause fix (screenshot/report: only tessera:atlas/blocks_alpha
    // ever appears in the per-atlas VRAM listing; every other static atlas
    // -- gui.png, particles.png, armor_trims.png, etc, 13 of them per
    // debug.log's "static sprites additionally stitched" lines -- is
    // classified and logged but never actually compressed or uploaded).
    //
    // SpriteRoutingMixin fires once per SOURCE TextureAtlas.stitch() call
    // (there are ~14 per reload: blocks, gui, particles, and every other
    // vanilla atlas Tessera isn't blacklisted against), but this class only
    // ever owned ONE opaque/alpha atlas pair shared across all of them. The
    // previous design ran a fully independent beginSplitStitch() ->
    // tessera$pendingResult = result per source atlas, each one
    // unconditionally overwriting whatever the previous source atlas had
    // just produced. Every one of those prepare-phase stitches (see
    // tessera$stitchSplit) runs on the background executor and all of them
    // complete before ANY atlas's apply-phase upload() runs (that ordering
    // is Minecraft's own PreparationBarrier guarantee -- every
    // SpriteLoader.stitch() across every reload listener finishes before
    // the barrier releases the apply phase for any of them). That meant
    // whichever source atlas's stitch happened to finish LAST always won
    // (blocks.png in this log, being the largest and therefore slowest),
    // and applyPendingSplitStitch() -- triggered by the FIRST atlas's own
    // upload() reaching TextureAtlasUploadMixin -- consumed and nulled
    // tessera$pendingResult before any of the 13 earlier, already-
    // discarded results could ever be applied.
    //
    // Fix: accumulate every source atlas's static-sprite contribution here
    // during the prepare phase instead of stitching+joining synchronously
    // per source atlas (see tessera$accumulateStaticSprites), then run
    // exactly ONE combined split-stitch over the merged list, triggered
    // from the apply phase (see tessera$triggerMergedStitchIfNeeded,
    // called from TextureAtlasUploadMixin) the first time any atlas's
    // upload() fires this reload -- by then the barrier guarantees every
    // prepare-phase contribution has already landed. Synchronized rather
    // than a lock-free structure: contributions arrive concurrently from
    // multiple SpriteLoader.stitch() background-executor invocations, but
    // this only runs once per reload (not a hot path), so a simple
    // synchronized block is the correct, simplest-safe choice here.
    private final List<SpriteContents> tessera$accumulatedStaticSprites = new ArrayList<>();
    private Executor tessera$accumulatedExecutor;

    // Reload-generation counter. Bumped once per fully-applied reload (see
    // applyPendingSplitStitch) -- tessera$triggerMergedStitchIfNeeded is
    // keyed off this so it only ever runs the merged stitch once per reload
    // cycle, regardless of how many of the ~14 source atlases' apply-phase
    // callbacks call it (see TextureAtlasUploadMixin). Minecraft's own
    // ReloadableResourceManager serializes reload cycles (a new reload's
    // prepare phase cannot begin until the previous one's apply phase has
    // fully resolved), so there is no window where two reloads' accumulator
    // contributions could interleave -- each reload's accumulateStaticSprites
    // calls, single tessera$triggerMergedStitchIfNeeded trigger, and single
    // applyPendingSplitStitch consumption happen strictly before the next
    // reload's first accumulateStaticSprites call.
    private long tessera$reloadGeneration = 0L;
    private long tessera$mergedStitchTriggeredGeneration = -1L;
    private CompletableFuture<Void> tessera$mergedStitchFuture;

    // Published atomically alongside tessera$spriteRouting once per reload
    // (see tessera$applyOnRenderThread) -- tracks which atlases actually
    // got a real glTexImage2D/glCompressedTexImage2D call this reload via
    // TextureAtlas.upload(). An atlas with zero routed sprites never has
    // upload() called on it (see opaqueHasSprites/alphaHasSprites there),
    // so its GL texture name -- if one exists at all -- has no defined
    // storage. Binding + sampling such a texture is what produced the
    // per-frame GL_INVALID_OPERATION flood in LevelRenderHandler; this set
    // is the single source of truth that render code must check before
    // ever calling atlasFor(target).getId().
    private volatile Set<AtlasSplitTarget> tessera$uploadedTargets = Set.of();

    public TextureAtlas atlasFor(AtlasSplitTarget target) {
        return target == AtlasSplitTarget.OPAQUE ? tessera$opaqueAtlas : tessera$alphaAtlas;
    }

    /**
     * Whether {@code target}'s atlas has valid, sampleable GL storage for
     * the current reload. Must be checked before any render code binds
     * {@link #atlasFor} for this target -- an empty bucket this reload
     * (zero static sprites routed here) means upload() was never called
     * and the underlying texture name has no defined storage.
     */
    public boolean hasContent(AtlasSplitTarget target) {
        return tessera$uploadedTargets.contains(target);
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

    @SuppressWarnings("DataFlowIssue")
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
     * Accumulates one source atlas's worth of static sprites (pulled out of
     * vanilla's own {@code SpriteLoader.stitch()} call by
     * {@code SpriteRoutingMixin}) into this reload's combined pool, instead
     * of immediately stitching them in isolation. See this class's
     * {@code tessera$accumulatedStaticSprites} field doc for why: every
     * source atlas Tessera isn't blacklisted against (there are ~14 per
     * reload -- blocks, gui, particles, armor_trims, and so on) reaches
     * this method independently on the background executor, and only a
     * single combined stitch pass over ALL of their contributions -- not
     * one independent stitch per contributor -- can ever populate the two
     * shared split atlases correctly, since {@link #tessera$opaqueAtlas}/
     * {@link #tessera$alphaAtlas} are one physical pair, not one per source
     * atlas.
     *
     * <p>Detects the start of a new reload by checking whether the
     * previous reload's merged stitch has already been triggered (see
     * {@link #tessera$triggerMergedStitchIfNeeded}) -- if so, this
     * contribution cannot belong to that already-triggered reload (its
     * prepare phase is long over by construction; a reload's prepare-phase
     * calls cannot outlive that same reload's own apply phase, and
     * triggering only ever happens from the apply phase), so it must be
     * the first contribution of the next one, and
     * {@link #tessera$reloadGeneration} advances to match. This is the
     * only place the generation counter changes now -- {@link #applyPendingSplitStitch}
     * no longer touches it, since bumping there was firing once per
     * non-blacklisted atlas's apply call (~14 times per reload) rather
     * than once per reload, desynchronizing {@link #tessera$mergedStitchTriggeredGeneration}
     * from {@link #tessera$reloadGeneration} mid-reload and causing
     * {@link #tessera$triggerMergedStitchIfNeeded} to incorrectly re-run
     * the stitch for the 2nd through ~14th atlas of the very same reload.
     *
     * @param allStaticSprites every non-dynamic sprite vanilla would have
     *                         stitched into this one source atlas this
     *                         reload
     * @param executor         the same background executor vanilla's own
     *                         stitch call was given for this source atlas;
     *                         the last contribution to arrive is the one
     *                         whose executor actually gets used for the
     *                         merged stitch (see
     *                         {@link #tessera$triggerMergedStitchIfNeeded}),
     *                         but every contributor passes the same
     *                         reload-wide background executor in practice,
     *                         so which one "wins" has no observable effect
     */
    public synchronized void accumulateStaticSprites(List<SpriteContents> allStaticSprites, Executor executor) {
        if (tessera$mergedStitchTriggeredGeneration == tessera$reloadGeneration) {
            // The current generation's merged stitch has already been
            // triggered (from a previous reload's apply phase) -- this
            // contribution belongs to a new reload starting up. Advance
            // the generation so this reload gets its own fresh trigger
            // window; the accumulator is already guaranteed empty at this
            // point (tessera$triggerMergedStitchIfNeeded drained it when it
            // triggered), so there is nothing to clear here.
            tessera$reloadGeneration++;
        }
        tessera$accumulatedStaticSprites.addAll(allStaticSprites);
        tessera$accumulatedExecutor = executor;
    }

    public static void setDispatching(boolean value) {
        IS_DISPATCHING.set(value);
    }

    public static boolean isDispatching() {
        return IS_DISPATCHING.get();
    }

    /**
     * Triggers the single combined split-stitch for this reload's
     * accumulated static-sprite pool, exactly once. Safe to call from
     * every source atlas's apply-phase callback (see
     * {@code TextureAtlasUploadMixin}) -- by the time ANY atlas reaches its
     * apply phase, Minecraft's own {@code PreparationBarrier} guarantees
     * every reload listener's prepare phase (including every
     * {@link #accumulateStaticSprites} call for this reload) has already
     * completed, so it is always safe to snapshot and consume the
     * accumulator the first time this fires. Subsequent calls this same
     * reload (from the other ~13 source atlases' own apply phases) are
     * no-ops.
     */
    public synchronized CompletableFuture<Void> tessera$triggerMergedStitchIfNeeded() {
        if (tessera$mergedStitchTriggeredGeneration == tessera$reloadGeneration) {
            return tessera$mergedStitchFuture;
        }
        tessera$mergedStitchTriggeredGeneration = tessera$reloadGeneration;

        List<SpriteContents> merged = List.copyOf(tessera$accumulatedStaticSprites);
        Executor executor = tessera$accumulatedExecutor;
        tessera$accumulatedStaticSprites.clear();

        if (merged.isEmpty() || executor == null) {
            tessera$mergedStitchFuture = CompletableFuture.completedFuture(null);
            return tessera$mergedStitchFuture;
        }

        // Guard against re-entrancy: tessera$opaqueAtlas/tessera$alphaAtlas
        // (see tessera$stitchSplit below) are themselves TextureAtlas
        // instances and therefore carry SpriteRoutingMixin too -- without
        // this guard, their own internal SpriteLoader.create(...).stitch(...)
        // calls (fired synchronously inside tessera$stitchSplit, on
        // whichever thread calls this method) would recurse straight back
        // into tessera$captureStitchArgs and re-accumulate Tessera's own
        // already-split sprites into the pool for the *next* reload, which
        // would then grow unboundedly reload over reload. This guard used
        // to live in SpriteRoutingMixin itself (wrapping its own call into
        // beginSplitStitch) back when that mixin drove the stitch
        // synchronously; now that the merged stitch is triggered from here
        // instead (see class doc's second root-cause fix), the guard has to
        // move to wherever tessera$stitchSplit is actually invoked from.
        setDispatching(true);
        try {
            tessera$mergedStitchFuture = tessera$stitchSplit(merged, executor)
                    .thenAccept(result -> tessera$pendingResult = result);
        } finally {
            setDispatching(false);
        }
        return tessera$mergedStitchFuture;
    }

    /**
     * Applies whatever {@link #tessera$triggerMergedStitchIfNeeded}
     * produced for this reload's combined static-sprite pool. Must run on
     * the render thread, after the merged stitch future has completed --
     * {@code TextureAtlasUploadMixin} is responsible for sequencing this
     * the same way vanilla sequences its own reload's apply phase, since
     * Tessera's two extra atlases are riding along on the same reload cycle
     * rather than running their own independent {@link #reload}.
     */
    public void applyPendingSplitStitch(ProfilerFiller profiler) {
        StitchResult result = tessera$pendingResult;
        tessera$pendingResult = null;
        // Bumped unconditionally, even when result is null (every source
        // atlas had zero static sprites this reload -- see
        // tessera$triggerMergedStitchIfNeeded's merged.isEmpty() branch,
        // which deliberately never populates tessera$pendingResult in that
        // case since there is nothing to apply). Without this, a
        // zero-static-sprite reload would leave tessera$reloadGeneration
        // and tessera$mergedStitchTriggeredGeneration equal to each other
        // AND unchanged from before this reload started, so the very next
        // reload's first tessera$triggerMergedStitchIfNeeded call would
        // incorrectly read as "already triggered this generation" and
        // return the stale completed-with-nothing future instead of
        // running a fresh stitch over whatever that next reload actually
        // accumulated.
        tessera$reloadGeneration++;
        if (result == null) {
            return;
        }
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

        if (Bc1ComputeSupport.isSupported() && Bc1TextureFormatSupport.isSupported()) {
            ByteBuffer baseRgba8 = AtlasCompressionDriver.assembleAtlasBuffer(
                    AtlasSplitTarget.OPAQUE.name(), preparations.regions().values(), preparations.width(), preparations.height());
            if (baseRgba8 == null) {
                LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (native buffer assembly unavailable); remains uncompressed RGBA8.",
                        AtlasSplitTarget.OPAQUE.atlasLocation());
                return new OpaqueBackgroundResult(
                        new AtlasCompressionDriver.CompressedAtlas(AtlasSplitTarget.OPAQUE.atlasLocation(), CompressionPipeline.Target.BC1, List.of(), preparations.mipLevel()),
                        null);
            }
            // Deferred to the render thread, see tessera$applyOnRenderThread.
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

    @SuppressWarnings("DataFlowIssue")
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

            // Debug groups added around vanilla's own TextureAtlas.upload()
            // calls (not just AtlasCompressionDriver's later compressed
            // upload) because debug.log timing showed the bulk of the
            // GL_INVALID_OPERATION flood landing here -- between "Created:
            // ...blocks_alpha-atlas" and "Atlas ...blocks_alpha stitched
            // with 505 sprites", i.e. during vanilla's own per-sprite
            // glTexSubImage2D loop inside upload(), well before
            // AtlasCompressionDriver.upload() (BC7's compressed re-upload)
            // even runs. This is Mojang's own TextureAtlas class -- we
            // cannot add logging inside its per-sprite loop itself without
            // a much more invasive mixin -- but a debug group around the
            // whole call at least narrows every async GlDebug message
            // fired during it to "somewhere in this specific atlas's
            // vanilla upload", instead of the reload as a whole.
            if (opaqueHasSprites) {
                GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0,
                        "tessera:vanilla-upload:" + AtlasSplitTarget.OPAQUE.atlasLocation());
                try {
                    tessera$opaqueAtlas.upload(result.opaquePreparations());
                } finally {
                    GL43.glPopDebugGroup();
                }
            }
            if (alphaHasSprites) {
                GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0,
                        "tessera:vanilla-upload:" + AtlasSplitTarget.ALPHA.atlasLocation());
                try {
                    tessera$alphaAtlas.upload(result.alphaPreparations());
                } finally {
                    GL43.glPopDebugGroup();
                }
            }
            tessera$spriteRouting = result.routing();
            LOGGER.info("Tessera split atlases stitched: {} opaque sprites, {} alpha sprites.",
                    result.opaquePreparations().regions().size(),
                    result.alphaPreparations().regions().size());

            AtlasCompressionDriver.CompressedAtlas opaqueCompressed = result.opaqueCompressed();
            if (opaqueHasSprites && opaqueCompressed == null && result.opaqueAssembledBuffer() != null) {
                // Debug group around the BC1 GPU-compute dispatch itself,
                // matching the convention AtlasCompressionDriver#upload
                // already uses -- previously nothing bracketed this call,
                // so any async GlDebug message the driver emitted during
                // dispatch was indistinguishable from one emitted a moment
                // earlier during vanilla's own TextureAtlas.upload() for
                // this same atlas (see Bc1ComputeEncoder#encode's own fix
                // for the related stale-glGetError() misattribution this
                // ambiguity caused).
                GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0,
                        "tessera:bc1-compute-dispatch:" + AtlasSplitTarget.OPAQUE.atlasLocation());
                try {
                    opaqueCompressed = AtlasCompressionDriver.compressBc1OnRenderThread(
                            AtlasSplitTarget.OPAQUE.atlasLocation(),
                            result.opaqueAssembledBuffer(),
                            result.opaquePreparations().width(),
                            result.opaquePreparations().height(),
                            result.opaquePreparations().mipLevel());
                } finally {
                    GL43.glPopDebugGroup();
                }
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
            // Was missing the "compressed result actually present" half of
            // the guard the opaque branch above already has. alphaHasSprites
            // only proves the bucket routed sprites here; it says nothing
            // about whether result.alphaCompressed() is a non-null
            // CompressedAtlas for THIS reload (background compression can
            // legitimately not have produced one yet -- no GPU-compute
            // render-thread fallback exists on the alpha/BC7 path the way
            // compressBc1OnRenderThread covers opaque above). Without this
            // check, upload() ran against whatever GL name
            // tessera$alphaAtlas.getId() currently held -- 0, or a stale id
            // left over from a prior reload -- and issued
            // glCompressedTexImage2D against it every time this raced: the
            // GL_INVALID_OPERATION flood in debug.log (same object id,
            // thousands of repeats, always the alpha atlas and never
            // opaque, since only this branch was missing the check).
            long alphaResident = (alphaHasSprites && result.alphaCompressed() != null)
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

            // Published together with tessera$spriteRouting below (same
            // reload, same publish point) so a render-thread read of one
            // is never stale relative to the other. Gated on *HasSprites
            // alone, not on *Resident -- an atlas that fell back to
            // vanilla's uncompressed RGBA8 path (opaqueResident < 0 above,
            // compression skipped/failed) still had TextureAtlas.upload()
            // called on it a few lines up and therefore has valid,
            // sampleable GL storage; only the "zero sprites routed here"
            // case leaves the texture name without defined storage.
            Set<AtlasSplitTarget> uploaded = EnumSet.noneOf(AtlasSplitTarget.class);
            if (opaqueHasSprites) {
                uploaded.add(AtlasSplitTarget.OPAQUE);
            }
            if (alphaHasSprites) {
                uploaded.add(AtlasSplitTarget.ALPHA);
            }
            this.tessera$uploadedTargets = uploaded;
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