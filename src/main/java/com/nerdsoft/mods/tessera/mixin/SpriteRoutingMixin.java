package com.nerdsoft.mods.tessera.mixin;

import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.config.TesseraConfig;
import com.nerdsoft.mods.tessera.config.TesseraRulesManager;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * <h2>Root-cause fix (see debug.log: 28024 "Missing textures in model ..."
 * warnings from {@code ModelManager}, immediately followed by
 * "Wrapped 28024 block state models ...")</h2>
 * The previous design used {@code @ModifyVariable} to replace vanilla's
 * {@code allSprites} stitch() argument with a list containing only the
 * dynamic sprites, before vanilla's {@code Stitcher} ran. That made every
 * static sprite disappear from vanilla's own atlas for the remainder of
 * this reload. {@code ModelBakery}/{@code ModelBakerImpl} resolve every
 * {@code BlockElementFace}'s texture reference against
 * {@code TextureAtlas.getSprite(name)} on the *vanilla* atlas at bake
 * time, on the same reload -- since the static sprite was never stitched
 * there, every one of those lookups permanently baked
 * {@code MissingTextureAtlasSprite}'s UVs into the quad. Baking cannot be
 * re-run once this happens; {@link com.nerdsoft.mods.tessera.render.TesseraModelWrapper}
 * and {@link com.nerdsoft.mods.tessera.render.TesseraSectionGeometryHandler}
 * only suppress/re-collect *already-baked* quads at compile time, so they
 * were re-collecting quads whose UVs were already permanently wrong --
 * hence static geometry rendering invisible (sampling the 0-alpha corner
 * of {@code MissingTextureAtlasSprite}) rather than merely mis-textured.
 *
 * <p>Fix: vanilla's {@code allSprites} argument is <em>never modified</em>
 * any more. Vanilla stitches, bakes, and renders every static sprite on
 * its own atlas exactly as it always has -- this mixin no longer touches
 * that parameter at all. Tessera instead stitches an <em>additional</em>,
 * independent copy of the static-sprite subset onto its own two atlases
 * purely so {@link com.nerdsoft.mods.tessera.atlas.AtlasCompressionDriver}
 * has BC1/BC7-compressed texture data and {@code routingFor} can tell
 * {@code TesseraModelWrapper}/{@code TesseraSectionGeometryHandler} which
 * atlas to bind against at *render* time. Vanilla's own bake is
 * untouched and therefore always has correct, present UVs to hand to
 * those two classes to re-collect -- they were already doing the right
 * thing with whatever quads they were given; the given quads were the
 * problem, not the collection logic.
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteRoutingMixin {

    @Unique
    private static final Logger tessera$LOGGER = LoggerFactory.getLogger("Tessera/SpriteRoutingMixin");

    @Shadow
    @Final
    private ResourceLocation location;

    /**
     * Guards against re-entrancy: {@link com.nerdsoft.mods.tessera.atlas.TesseraSplitAtlasManager}
     * calls {@code SpriteLoader.create(atlas).stitch(...)} on its own two
     * atlases as part of servicing this same interception, which would
     * otherwise recurse back into this mixin for those calls too.
     */
    @Unique
    private static final ThreadLocal<Boolean> tessera$dispatching = ThreadLocal.withInitial(() -> false);

    /**
     * Read-only observer at {@code HEAD}: no longer uses
     * {@code @ModifyVariable}, since {@code allSprites} must reach
     * vanilla's own stitch body completely unmodified (see class doc).
     * Splits off a *copy* of the static subset and hands it to
     * {@link com.nerdsoft.mods.tessera.atlas.TesseraSplitAtlasManager}
     * as a side effect, without altering what the real method body sees.
     */
    @Inject(method = "stitch", at = @At("HEAD"))
    private void tessera$captureStitchArgs(List<SpriteContents> allSprites, int maxMipLevel, Executor executor, CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir) {
        if (tessera$dispatching.get()) {
            return;
        }
        if (!tessera$shouldSplit()) {
            return;
        }

        List<SpriteContents> staticSprites = allSprites.stream()
                .filter(contents -> contents.createTicker() == null)
                .toList();

        if (staticSprites.isEmpty()) {
            return;
        }

        tessera$dispatching.set(true);
        CompletableFuture<Void> splitStitch;
        try {
            splitStitch = TesseraClient.SPLIT_ATLAS_MANAGER.beginSplitStitch(staticSprites, executor);
        } finally {
            tessera$dispatching.set(false);
        }

        try {
            splitStitch.join();
        } catch (RuntimeException e) {
            tessera$LOGGER.warn("Split-atlas stitch failed for source atlas {}; sprites remain compressed-off, routing disabled for this reload.",
                    this.location, e);
            return;
        }

        tessera$LOGGER.info("Atlas {}: {} static sprites additionally stitched onto Tessera's split atlases for compression; vanilla's own atlas is untouched.",
                this.location, staticSprites.size());
    }

    @Unique
    private boolean tessera$shouldSplit() {
        if (TesseraConfig.DISABLE_NATIVE_COMPRESSION.get()) {
            return false;
        }
        return !TesseraRulesManager.BLACKLISTED_ATLASES.contains(this.location.toString());
    }
}
