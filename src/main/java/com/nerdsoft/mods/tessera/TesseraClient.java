package com.nerdsoft.mods.tessera;

import com.nerdsoft.mods.tessera.atlas.TesseraSplitAtlasManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-only static holder for singletons and thread-local state.
 */
public final class TesseraClient {

    public static final TesseraSplitAtlasManager SPLIT_ATLAS_MANAGER = new TesseraSplitAtlasManager();

    private static final ThreadLocal<ResourceLocation> ACTIVE_QUAD_SPRITE = new ThreadLocal<>();

    private TesseraClient() {
    }

    @SuppressWarnings("unused")
    public static void setActiveQuadSprite(ResourceLocation location) {
        if (location == null) {
            ACTIVE_QUAD_SPRITE.remove();
        } else {
            ACTIVE_QUAD_SPRITE.set(location);
        }
    }

    @SuppressWarnings("unused")
    public static ResourceLocation getActiveQuadSprite() {
        return ACTIVE_QUAD_SPRITE.get();
    }
}