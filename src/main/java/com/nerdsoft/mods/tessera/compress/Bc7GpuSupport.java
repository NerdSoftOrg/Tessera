package com.nerdsoft.mods.tessera.compress;

import org.lwjgl.opengl.ARBTextureCompressionBPTC;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

public final class Bc7GpuSupport {

    private static volatile Boolean supported;

    private Bc7GpuSupport() {
    }

    public static boolean isSupported() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }
        return detectAndCache();
    }

    private static synchronized boolean detectAndCache() {
        if (supported != null) {
            return supported;
        }

        boolean found = false;
        try {
            var caps = GL.getCapabilities();
            if (caps.GL_ARB_texture_compression_bptc || caps.OpenGL42) {
                found = true;
            }
        } catch (Throwable ignored) {
        }

        if (!found) {
            int formatCount = GL11.glGetInteger(GL13.GL_NUM_COMPRESSED_TEXTURE_FORMATS);
            int[] formats = new int[formatCount];
            GL11.glGetIntegerv(GL13.GL_COMPRESSED_TEXTURE_FORMATS, formats);

            for (int format : formats) {
                if (format == ARBTextureCompressionBPTC.GL_COMPRESSED_RGBA_BPTC_UNORM_ARB) {
                    found = true;
                    break;
                }
            }
        }

        supported = found;
        return found;
    }
}
