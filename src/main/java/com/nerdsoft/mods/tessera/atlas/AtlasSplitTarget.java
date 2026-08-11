package com.nerdsoft.mods.tessera.atlas;

import com.nerdsoft.mods.tessera.compress.CompressionPipeline;
import net.minecraft.resources.ResourceLocation;

/**
 * The two independently-stitched, independently-owned static atlases that
 * replace the old same-atlas "bucket" hack. Each value here corresponds to
 * a real {@code net.minecraft.client.renderer.texture.TextureAtlas}
 * instance with its own GL texture ID, its own {@code SpriteLoader.stitch()}
 * pass, and therefore its own self-consistent UV space -- there is no
 * cross-atlas coordinate reuse anywhere in this design, which is what the
 * previous single-atlas-three-textures approach got wrong.
 *
 * <p>Only <em>static</em> (non-animated) sprites are split this way.
 * Animated sprites (anything with a {@code Ticker}) are deliberately left
 * on vanilla's own {@code TextureAtlas.LOCATION_BLOCKS} / equivalent, sized
 * down to just that subset -- see {@link SpriteClassifier}.
 */
public enum AtlasSplitTarget {

    /**
     * Fully opaque sprites (every texel alpha == 255) plus punch-through
     * sprites (alpha is binary, never partial). Compressed as BC1, using
     * DXT1 punch-through mode for the latter -- both fit in the same 4bpp
     * format and the same physical atlas.
     */
    OPAQUE(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/blocks_opaque"),
            CompressionPipeline.Target.BC1
    ),

    /**
     * Sprites with genuine partial-coverage alpha (anti-aliased edges,
     * soft glow, blended overlays). Compressed as BC7 to preserve that
     * fidelity -- BC1 punch-through cannot represent an in-between alpha
     * value.
     */
    ALPHA(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/blocks_alpha"),
            CompressionPipeline.Target.BC7
    );

    private final ResourceLocation atlasLocation;
    private final CompressionPipeline.Target compressionTarget;

    AtlasSplitTarget(ResourceLocation atlasLocation, CompressionPipeline.Target compressionTarget) {
        this.atlasLocation = atlasLocation;
        this.compressionTarget = compressionTarget;
    }

    public ResourceLocation atlasLocation() {
        return atlasLocation;
    }

    public CompressionPipeline.Target compressionTarget() {
        return compressionTarget;
    }
}