package com.nerdsoft.mods.tessera.atlas;

/**
 * The four physical-texture buckets a sprite can be routed into before
 * stitching. Each bucket becomes its own independent GL texture with its
 * own bin-packed layout, because a single GL texture can only have one
 * internal format — see SpriteClassifier and SpriteLoaderStitchMixin for
 * where this is applied.
 *
 * <p>Ordinal order matters: it's used as the routing-table index stored
 * per-sprite (see SpriteLoaderStitchMixin$AtlasSplit#bucketByName), and as
 * the array index for the four parallel width/height/texture-id arrays
 * threaded through SpriteLoaderMixin. Do not reorder without updating both.
 */
public enum SpriteBucket {

    /**
     * Fully opaque, non-animated sprites (every texel has alpha == 255).
     * Compressed to BC1 (4bpp) — no alpha channel is stored at all, so
     * this is the biggest per-texel VRAM win of the four buckets.
     */
    OPAQUE_BC1,

    /**
     * Non-animated sprites whose alpha channel is binary — every texel is
     * either fully opaque (255) or fully transparent (0), never a value in
     * between. Compressed to BC1 using DXT1's punch-through mode
     * (GL_COMPRESSED_RGBA_S3TC_DXT1_EXT), which stores 1-bit alpha at the
     * same 4bpp rate as OPAQUE_BC1 — cheaper than ALPHA_BC7, and more
     * correct than OPAQUE_BC1 (which has no alpha channel to represent
     * cutouts at all). The canonical Minecraft example is leaves and
     * cutout glass: every texel is either the leaf/glass color at full
     * opacity or fully see-through, with no soft edges.
     */
    PUNCHTHROUGH_BC1,

    /**
     * Non-animated sprites with genuine partial transparency — at least
     * one texel has an alpha value strictly between 0 and 255 (e.g.
     * anti-aliased icon edges, soft glows, smoothly faded overlays).
     * Compressed to BC7 (8bpp) to preserve that partial-coverage alpha
     * fidelity, since BC1's punch-through mode can only represent fully
     * opaque or fully transparent, never partial.
     */
    ALPHA_BC7,

    /**
     * Sprites with a Ticker (createTicker() != null) — animated content
     * that gets re-uploaded every tick. Left as plain uncompressed RGBA8
     * and uploaded through vanilla's own path, since BC-encoding this
     * bucket every frame would be far too slow to be worth it.
     */
    DYNAMIC_RGBA8;

    @SuppressWarnings("unused")
    public static final int COUNT = values().length;
}