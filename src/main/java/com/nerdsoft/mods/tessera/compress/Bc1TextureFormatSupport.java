package com.nerdsoft.mods.tessera.compress;

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
 * the resulting blocks can legally be uploaded as S3TC. Where the driver
 * doesn't expose {@code GL_EXT_texture_compression_s3tc} (core since GL 1.3
 * via the equivalent ARB path, but not guaranteed on every implementation,
 * and explicitly disableable on some), that call raises
 * {@code GL_INVALID_OPERATION} for the unsupported internal format and
 * leaves the texture object's storage for that level undefined -- which is
 * what produced the intermittent invisible block textures: whether the
 * atlas then samples as garbage depends on driver-specific handling of an
 * incomplete texture image, not on anything Tessera controls once the call
 * has already failed.
 */
public final class Bc1TextureFormatSupport {

    private static volatile Boolean supported;

    private Bc1TextureFormatSupport() {
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
        }

        supported = found;
        return found;
    }
}