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
            CompressionPipeline.Target.BC1,
            true
    ),

    /**
     * Sprites with genuine partial-coverage alpha (anti-aliased edges,
     * soft glow, blended overlays). Compressed as BC7 to preserve that
     * fidelity -- BC1 punch-through cannot represent an in-between alpha
     * value.
     */
    ALPHA(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/blocks_alpha"),
            CompressionPipeline.Target.BC7,
            true
    ),

    /**
     * Reserved for {@code minecraft:textures/atlas/gui}'s opaque/
     * punch-through sprites. Defined here so the F3 breakdown and
     * {@link com.nerdsoft.mods.tessera.gui.DebugOverlay} naming can refer
     * to it, but {@link com.nerdsoft.mods.tessera.mixin.SpriteRoutingMixin}
     * does not route any sprites here yet -- GUI blits bind the vanilla
     * atlas directly by {@link ResourceLocation}, with no equivalent to
     * {@code ModelWrapper}/{@code SectionGeometryHandler}/
     * {@code LevelRenderHandler} to rebind them onto a separate physical
     * texture. {@link #eligible()} reflects that: no static sprite is ever
     * classified against this target until a GUI-side rendering consumer
     * exists.
     */
    HUD_OPAQUE(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/hud_opaque"),
            CompressionPipeline.Target.BC1,
            false
    ),

    /**
     * Reserved for {@code minecraft:textures/atlas/gui}'s blended-alpha
     * sprites. See {@link #HUD_OPAQUE} for why this is defined but not
     * yet populated.
     */
    HUD_ALPHA(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/hud_alpha"),
            CompressionPipeline.Target.BC7,
            false
    );

    private final ResourceLocation atlasLocation;
    private final CompressionPipeline.Target compressionTarget;
    private final boolean eligible;

    AtlasSplitTarget(ResourceLocation atlasLocation, CompressionPipeline.Target compressionTarget, boolean eligible) {
        this.atlasLocation = atlasLocation;
        this.compressionTarget = compressionTarget;
        this.eligible = eligible;
    }

    public ResourceLocation atlasLocation() {
        return atlasLocation;
    }

    public CompressionPipeline.Target compressionTarget() {
        return compressionTarget;
    }

    /**
     * Whether a real, wired-up rendering consumer exists for this target.
     * {@link #OPAQUE}/{@link #ALPHA} are consumed by the block chunk-layer
     * render path; {@link #HUD_OPAQUE}/{@link #HUD_ALPHA} are not consumed
     * anywhere yet and must never receive routed sprites.
     */
    public boolean eligible() {
        return eligible;
    }
}
