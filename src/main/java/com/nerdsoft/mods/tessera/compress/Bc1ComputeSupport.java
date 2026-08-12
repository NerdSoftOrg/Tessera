package com.nerdsoft.mods.tessera.compress;

import com.nerdsoft.mods.tessera.atlas.SplitAtlasManager;
import org.lwjgl.opengl.GL;

/**
 * Capability check for {@link Bc1ComputeEncoder}'s GPU compute-shader BC1
 * path. Mirrors {@link Bc7GpuSupport}'s pattern exactly (volatile cache,
 * {@code GL.getCapabilities()} check) -- kept as a separate class rather
 * than folded into {@code Bc7GpuSupport} because the two check entirely
 * different capabilities (texture compression format support vs. compute
 * shader support) that happen to both gate compression paths; conflating
 * them would make a GPU lacking one but not the other report incorrectly
 * for whichever check runs second.
 *
 * <p>Compute shaders are core since OpenGL 4.3 -- {@code caps.OpenGL43}
 * alone is sufficient on any driver claiming that core version. The
 * {@code GL_ARB_compute_shader} extension check additionally covers GL 4.2
 * contexts that expose compute via extension rather than core support,
 * matching how {@link Bc7GpuSupport} checks both an extension and a core
 * version flag for BPTC.
 *
 * <h2>Render-thread warmup requirement</h2>
 * See {@link Bc1TextureFormatSupport}'s class doc for the full root-cause
 * writeup -- this class has the identical bug and identical fix.
 * {@code GL.getCapabilities()} is thread-local to whichever thread has a
 * GL context current; {@link SplitAtlasManager}'s background stitch
 * executor was the first real caller of {@link #isSupported()}, has no
 * context current, threw inside the try block, and permanently memoized
 * {@code false}. {@link #warmUp()} must be called from the render thread
 * (see {@code Tessera#onClientSetup}) before any background caller can
 * reach {@link #isSupported()} first.
 */
public final class Bc1ComputeSupport {

    private static volatile Boolean supported;

    private Bc1ComputeSupport() {
    }

    /**
     * Forces capability detection now, on whatever thread calls this --
     * callers MUST invoke this from the render thread before
     * {@link SplitAtlasManager}'s background stitch path can reach
     * {@link #isSupported()} first. No-op if already cached.
     */
    public static void warmUp() {
        isSupported();
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
            if (caps.OpenGL43 || caps.GL_ARB_compute_shader) {
                found = true;
            }
        } catch (Throwable ignored) {
            // No GL context current on this thread (background executor
            // call before warmUp() ran) -- do not memoize `false` here,
            // or the real driver capability is never consulted again.
            return false;
        }

        supported = found;
        return found;
    }
}
