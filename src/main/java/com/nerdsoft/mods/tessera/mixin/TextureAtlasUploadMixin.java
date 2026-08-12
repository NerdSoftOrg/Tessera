package com.nerdsoft.mods.tessera.mixin;

import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.config.RulesManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies whichever split-stitch result {@link SpriteRoutingMixin} queued
 * up for the current reload, at the same point vanilla uploads its own
 * atlas -- {@code TextureAtlas.upload(Preparations)}, always called from
 * the render thread as part of {@code ReloadableResourceManager}'s apply
 * phase. This is the correct/only safe place to call GL upload functions
 * for Tessera's two atlases: {@code SpriteLoader.stitch()} itself (where
 * {@code SpriteRoutingMixin} does its work) runs on a background executor
 * and must never touch GL directly.
 *
 * <p>Only fires for the specific {@code TextureAtlas} instance whose
 * location matches the source atlas Tessera is splitting (i.e. the same
 * one {@link SpriteRoutingMixin#tessera$shouldSplit} allowed through) --
 * every other atlas's {@code upload()} call (particles, GUI, etc. in this
 * pass) passes through to vanilla completely untouched.
 */
@Mixin(TextureAtlas.class)
@SuppressWarnings("JavadocReference")
public abstract class TextureAtlasUploadMixin {

    // Render-thread-only re-entrancy guard (see class doc). Not volatile:
    // every read and write happens on the render thread only, by the same
    // invariant that makes upload() itself render-thread-only, so there is
    // no cross-thread visibility requirement to pay for here.
    @Unique
    private static boolean tessera$applying = false;

    @Inject(method = "upload", at = @At("RETURN"))
    private void tessera$applySplitAtlases(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (tessera$applying) {
            // Re-entered from Tessera's own tessera$opaqueAtlas/tessera$alphaAtlas
            // upload() calls inside applyPendingSplitStitch() below -- those are
            // TextureAtlas instances too and carry this same mixin. Returning here
            // is correct and sufficient: applyPendingSplitStitch() already fully
            // drains tessera$pendingResult in the outer (non-re-entrant) call, so
            // there is nothing left to apply on the re-entrant one even if we let
            // it through -- returning early just skips redoing that no-op work
            // while the outer call's GL/RenderSystem bind state is still settling.
            return;
        }

        TextureAtlas self = (TextureAtlas) (Object) this;
        ResourceLocation atlasLocation = self.location();

        if (RulesManager.BLACKLISTED_ATLASES.contains(atlasLocation.toString())) {
            // Tessera's own two split atlases (tessera:atlas/blocks_opaque,
            // tessera:atlas/blocks_alpha) -- these ARE the re-entrant calls
            // tessera$applying guards against above; nothing to trigger or
            // apply from this atlas's own upload(). Every OTHER atlas
            // (blocks, gui, particles, and every other vanilla atlas
            // Tessera isn't blacklisted against) falls through below and is
            // a valid trigger point for the merged stitch, not just
            // whichever one used to be treated as "the" split source.
            return;
        }

        // TesseraSplitAtlasManager silently no-ops applyPendingSplitStitch
        // when nothing is queued (e.g. every source atlas this reload had
        // zero static sprites, or the merged stitch failed and was already
        // logged) -- safe to call unconditionally on every non-blacklisted
        // atlas's upload rather than needing this mixin to track which
        // atlas is "the" split one itself.
        tessera$applying = true;
        try {
            // Triggers (idempotently -- see that method's own doc comment)
            // the single combined split-stitch for this reload's whole
            // accumulated static-sprite pool, then blocks until it
            // completes. join() is safe here despite running on the render
            // thread: tessera$triggerMergedStitchIfNeeded's own CPU-side
            // work (classification, buffer assembly, CPU compression) all
            // runs on the background executor it was handed, and by this
            // point in the reload every prepare-phase contribution has
            // already landed (PreparationBarrier guarantee) -- this join()
            // is waiting on already-in-flight or already-complete
            // background work, not kicking off new render-thread-blocking
            // work itself.
            TesseraClient.SPLIT_ATLAS_MANAGER.tessera$triggerMergedStitchIfNeeded().join();
            TesseraClient.SPLIT_ATLAS_MANAGER.applyPendingSplitStitch(
                    Minecraft.getInstance().getProfiler());
        } finally {
            tessera$applying = false;
        }
    }
}