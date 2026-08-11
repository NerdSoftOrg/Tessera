package com.nerdsoft.mods.tessera.compress;

import org.lwjgl.opengl.GL;

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
            if (caps.OpenGL42 || caps.GL_ARB_texture_compression_bptc) {
                found = true;
            }
        } catch (Throwable ignored) {
        }

        supported = found;
        return found;
    }
}
