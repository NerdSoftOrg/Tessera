package com.nerdsoft.mods.tessera.compress;

import com.nerdsoft.mods.tessera.atlas.SplitAtlasManager;
import org.lwjgl.opengl.GL;

/**
 * Capability check for whether the driver can actually accept
 * {@code GL_COMPRESSED_RGB_S3TC_DXT1_EXT} / {@code GL_COMPRESSED_RGBA_S3TC_DXT1_EXT}
 * in {@code glCompressedTexImage2D}. Mirrors {@link Bc7GpuSupport}'s pattern
 * (volatile cache, {@code GL.getCapabilities()} check).
 *
 * <p>This is deliberately separate from {@link Bc1ComputeSupport}, which
 * checks an unrelated capability (compute-shader availability, for the
 * optional GPU-side encoder). Neither the CPU compression path
 * ({@code CompressionPipeline#compress}) nor the GPU compute path
 * ({@code AtlasCompressionDriver#compressBc1OnRenderThread}) ever checked
 * whether the driver actually exposes the S3TC texture-compression format
 * before {@code uploadCompressedLevel} fed the DXT1 enum straight into
 * {@code glCompressedTexImage2D} -- {@link Bc1ComputeSupport#isSupported()}
 * being {@code true} only guarantees a compute shader could run, not that
 * the resulting blocks can legally be uploaded as S3TC.
 *
 * <h2>Render-thread warmup requirement (root cause of the BC1-always-off bug)</h2>
 * {@code GL.getCapabilities()} returns the {@code GLCapabilities} bound to
 * whichever thread currently has a GL context current -- it is thread-local
 * state inside LWJGL, not a global snapshot. {@link SplitAtlasManager}'s
 * background-executor path ({@code tessera$prepareOpaqueInBackground}) was
 * the first and only caller of {@link #isSupported()} at runtime; that
 * method runs on a {@code Worker-Main-N} stitch thread, which never has a
 * GL context current. {@code GL.getCapabilities()} throws there, the
 * {@code catch (Throwable ignored)} below swallowed it, and {@code false}
 * was memoized into the {@code volatile Boolean supported} field
 * permanently -- {@link #isSupported()} short-circuits on the cached value
 * for the rest of the process, so the driver's real S3TC support was never
 * consulted again even once a GL context existed. This is why every BC1
 * compression attempt logged "SKIPPED (compression unavailable)" for the
 * opaque atlas on every reload, unconditionally, regardless of driver.
 * {@link Bc7GpuSupport} does not exhibit this bug only because
 * {@code Tessera#onClientSetup} happens to call it once via
 * {@code FMLClientSetupEvent#enqueueWork}, which runs on the render thread
 * and warms its cache correctly before any background caller could poison
 * it -- no such warmup existed for this class or {@link Bc1ComputeSupport}.
 * {@link #warmUp()} closes that gap; callers must invoke it from a
 * render-thread context (e.g. {@code FMLClientSetupEvent#enqueueWork},
 * mirroring the existing BC7 warmup) before {@link #isSupported()} can ever
 * be reached from a background thread.
 */
public final class Bc1TextureFormatSupport {

    private static volatile Boolean supported;

    private Bc1TextureFormatSupport() {
    }

    /**
     * Forces capability detection now, on whatever thread calls this --
     * callers MUST invoke this from the render thread (a thread with a
     * current GL context) before {@link SplitAtlasManager}'s background
     * stitch path can reach {@link #isSupported()} first. A no-op if
     * already cached, so safe to call defensively on every client-setup
     * pass without re-querying the driver each time.
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
            // GL_EXT_texture_compression_s3tc is the extension that actually
            // gates GL_COMPRESSED_RGB_S3TC_DXT1_EXT / ..._RGBA_..._DXT1_EXT.
            // There is no core-version fallback the way there is for BPTC
            // (GL 4.2) or compute shaders (GL 4.3) -- S3TC never became core
            // GL due to patent history, so the extension flag is the only
            // signal, on every version, on every platform.
            if (caps.GL_EXT_texture_compression_s3tc) {
                found = true;
            }
        } catch (Throwable ignored) {
            // Reachable when no GL context is current on the calling
            // thread (e.g. this is hit from a background executor before
            // warmUp() has run). Deliberately does NOT cache `found` in
            // this branch -- see below.
            return false;
        }

        supported = found;
        return found;
    }
}
