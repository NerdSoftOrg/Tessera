package com.nerdsoft.mods.tessera.api;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public class TesseraAtlasCompressEvent extends Event implements ICancellableEvent {

    private final TextureAtlas atlas;

    public TesseraAtlasCompressEvent(TextureAtlas atlas) {
        this.atlas = atlas;
    }

    public TextureAtlas getAtlas() {
        return atlas;
    }
}