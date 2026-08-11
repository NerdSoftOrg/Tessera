package com.nerdsoft.mods.tessera.atlas;

import net.minecraft.client.renderer.texture.SpriteContents;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Packs a flat list of {@link SpriteContents} into a single direct RGBA8
 * pixel buffer plus parallel width/height/srcOffset arrays, ready to hand to
 * {@link NativeFamilyDetector#detect}. Shared by any call site that needs to
 * batch a set of sprites into one native call before they have a real atlas
 * position (srcOffset-only, no destX/destY) -- e.g. classification and
 * dedup, both of which only care about pixel content, not final placement.
 */
public final class SpriteLayoutTable {
    private final int[] widths;
    private final int[] heights;
    private final int[] srcOffsets;
    private final ByteBuffer pixels;

    private SpriteLayoutTable(int[] widths, int[] heights, int[] srcOffsets, ByteBuffer pixels) {
        this.widths = widths;
        this.heights = heights;
        this.srcOffsets = srcOffsets;
        this.pixels = pixels;
    }

    @SuppressWarnings("unused")
    public int width(int index) {
        return widths[index];
    }

    @SuppressWarnings("unused")
    public int height(int index) {
        return heights[index];
    }

    @SuppressWarnings("unused")
    public int srcOffset(int index) {
        return srcOffsets[index];
    }

    /**
     * The packed RGBA8 pixel buffer, positioned at 0 and ready to read.
     */
    public ByteBuffer pixels() {
        return pixels;
    }

    /**
     * Builds a {@link NativeFamilyDetector.SpriteInput} list matching this
     * table's layout, with destX/destY left at 0 and tinted left false --
     * callers that need real atlas placement or tint info should build their
     * own SpriteInput list using this table's offset/dimension accessors
     * instead of this convenience method.
     */
    public List<NativeFamilyDetector.SpriteInput> toUntintedSpriteInputs() {
        List<NativeFamilyDetector.SpriteInput> inputs = new ArrayList<>(widths.length);
        for (int i = 0; i < widths.length; i++) {
            inputs.add(new NativeFamilyDetector.SpriteInput(srcOffsets[i], widths[i], heights[i], 0, 0, false));
        }
        return inputs;
    }

    /**
     * Computes the layout (widths/heights/srcOffsets) and allocates the
     * direct pixel buffer, but does not yet write pixel data -- callers that
     * need to bail out early on allocation failure (see SpriteClassifier)
     * should catch OutOfMemoryError/IllegalArgumentException around this call
     * before proceeding to {@link #packPixels}.
     */
    public static SpriteLayoutTable allocate(List<SpriteContents> sprites) {
        int count = sprites.size();
        int[] widths = new int[count];
        int[] heights = new int[count];
        int[] srcOffsets = new int[count];
        long totalPixelBytes = 0L;
        for (int i = 0; i < count; i++) {
            int width = sprites.get(i).width();
            int height = sprites.get(i).height();
            widths[i] = width;
            heights[i] = height;
            srcOffsets[i] = (int) totalPixelBytes;
            totalPixelBytes += (long) width * height * 4;
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect((int) totalPixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        return new SpriteLayoutTable(widths, heights, srcOffsets, pixels);
    }

    /**
     * Writes each sprite's original-image RGBA8 pixels into this table's
     * buffer at its assigned srcOffset, then rewinds the buffer to position 0
     * so it's ready to hand to the native bridge.
     */
    public void packPixels(List<SpriteContents> sprites) {
        for (int i = 0; i < sprites.size(); i++) {
            SpriteContents contents = sprites.get(i);
            int width = widths[i];
            int height = heights[i];
            pixels.position(srcOffsets[i]);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels.putInt(contents.getOriginalImage().getPixelRGBA(x, y));
                }
            }
        }
        pixels.rewind();
    }
}