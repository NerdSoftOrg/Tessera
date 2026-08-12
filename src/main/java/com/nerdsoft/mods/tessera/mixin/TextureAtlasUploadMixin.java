package com.nerdsoft.mods.tessera.mixin;

import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.config.RulesManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
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

    @Inject(method = "upload", at = @At("RETURN"))
    private void tessera$applySplitAtlases(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        TextureAtlas self = (TextureAtlas) (Object) this;
        ResourceLocation atlasLocation = self.location();

        if (RulesManager.BLACKLISTED_ATLASES.contains(atlasLocation.toString())) {
            return;
        }

        // TesseraSplitAtlasManager silently no-ops applyPendingSplitStitch
        // when nothing is queued (e.g. this atlas wasn't split, or the
        // split-stitch failed and SpriteRoutingMixin already logged and
        // bailed) -- safe to call unconditionally on every atlas's upload
        // rather than needing this mixin to track which atlas is "the"
        // split one itself.
        TesseraClient.SPLIT_ATLAS_MANAGER.applyPendingSplitStitch(
                Minecraft.getInstance().getProfiler());
    }
}