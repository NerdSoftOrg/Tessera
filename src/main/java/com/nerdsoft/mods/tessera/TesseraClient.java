package com.nerdsoft.mods.tessera;

import com.nerdsoft.mods.tessera.atlas.SplitAtlasManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-only static holder for singletons and thread-local state.
 */
public final class TesseraClient {

    public static final SplitAtlasManager SPLIT_ATLAS_MANAGER = new SplitAtlasManager();

    private static final ThreadLocal<ResourceLocation> ACTIVE_QUAD_SPRITE = new ThreadLocal<>();

    private TesseraClient() {
    }

    @SuppressWarnings("unused")
    public static ResourceLocation getActiveQuadSprite() {
        return ACTIVE_QUAD_SPRITE.get();
    }

    @SuppressWarnings("unused")
    public static void setActiveQuadSprite(ResourceLocation location) {
        if (location == null) {
            ACTIVE_QUAD_SPRITE.remove();
        } else {
            ACTIVE_QUAD_SPRITE.set(location);
        }
    }
}