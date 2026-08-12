package com.nerdsoft.mods.tessera.mixin;

import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.SplitAtlasManager;
import com.nerdsoft.mods.tessera.config.Config;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
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

@Mixin(SpriteLoader.class)
public abstract class SpriteRoutingMixin {

    @Unique
    private static final Logger tessera$LOGGER = LoggerFactory.getLogger("Tessera/SpriteRoutingMixin");

    @Shadow
    @Final
    private ResourceLocation location;

    @Inject(method = "stitch", at = @At("HEAD"))
    private void tessera$captureStitchArgs(List<SpriteContents> allSprites, int maxMipLevel, Executor executor, CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir) {
        if (SplitAtlasManager.isDispatching()) {
            return;
        }
        if (!tessera$shouldSplit()) {
            return;
        }

        boolean freezeAnimations = Config.DISABLE_ANIMATIONS_ATLASES.get().contains(this.location.toString());
        List<SpriteContents> staticSprites = allSprites.stream()
                .filter(contents -> contents.createTicker() == null || freezeAnimations)
                .toList();

        if (staticSprites.isEmpty()) {
            return;
        }

        TesseraClient.SPLIT_ATLAS_MANAGER.accumulateStaticSprites(staticSprites, executor);

        tessera$LOGGER.info("Atlas {}: {} static sprites queued for Tessera's combined split-atlas stitch; vanilla's own atlas is untouched.",
                this.location, staticSprites.size());
    }

    @Unique
    private boolean tessera$shouldSplit() {
        if (Config.DISABLE_NATIVE_COMPRESSION.get()) {
            return false;
        }
        return this.location.equals(TextureAtlas.LOCATION_BLOCKS);
    }
}