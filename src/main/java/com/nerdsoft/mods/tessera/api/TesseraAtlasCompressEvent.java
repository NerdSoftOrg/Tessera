package com.nerdsoft.mods.tessera.api;

import com.nerdsoft.mods.tessera.cache.AtlasCache.CompressedFormat;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

@SuppressWarnings("unused")
public abstract class TesseraAtlasCompressEvent extends Event {

    private final TextureAtlas atlas;

    public TesseraAtlasCompressEvent(TextureAtlas atlas) {
        this.atlas = atlas;
    }

    public TextureAtlas getAtlas() {
        return atlas;
    }

    public static class Pre extends TesseraAtlasCompressEvent implements ICancellableEvent {

        private CompressedFormat targetFormat;

        public Pre(TextureAtlas atlas, CompressedFormat defaultFormat) {
            super(atlas);
            this.targetFormat = defaultFormat;
        }

        public CompressedFormat getTargetFormat() {
            return targetFormat;
        }

        public void setTargetFormat(CompressedFormat targetFormat) {
            this.targetFormat = targetFormat;
        }
    }

    public static class Post extends TesseraAtlasCompressEvent {

        private final CompressedFormat appliedFormat;
        private final long vramBytesSaved;

        public Post(TextureAtlas atlas, CompressedFormat appliedFormat, long vramBytesSaved) {
            super(atlas);
            this.appliedFormat = appliedFormat;
            this.vramBytesSaved = vramBytesSaved;
        }

        public CompressedFormat getAppliedFormat() {
            return appliedFormat;
        }

        public long getVramBytesSaved() {
            return vramBytesSaved;
        }
    }
}