package com.nerdsoft.mods.tessera.compress;

import com.nerdsoft.mods.tessera.atlas.AtlasCompressionDriver;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * GPU compute-shader BC1 encoder, dispatching {@code bc1_encode.comp.glsl}
 * (bundled as a mod resource, see {@link #tessera$loadShaderSource}).
 * Render-thread only -- every method here issues GL calls and must not be
 * invoked from a background executor thread, unlike
 * {@link AtlasCompressionDriver#compress} which deliberately keeps CPU-side
 * work off the render thread. This class is the inverse: its entire
 * purpose is doing that same compression work on the GPU instead, which
 * necessarily means it lives on the render thread (GL context is
 * thread-affine).
 *
 * <h2>Quality tradeoff versus the CPU path</h2>
 * This encoder uses range-fit endpoint selection (block min/max color,
 * inset, nearest-palette-index assignment) -- see the shader's own doc
 * comment. This is faster but lower quality than {@code bc7enc_rdo}'s
 * exhaustive-search CPU encoding used elsewhere in this pipeline for BC7.
 * For BC1 specifically the quality gap is generally small (BC1's 4-color
 * palette leaves less room for a sophisticated search to improve on
 * range-fit than BC7's much larger mode/partition space does), but this
 * has not been visually verified against this project's actual atlas
 * content -- spot-check compressed opaque-atlas output against the
 * previous CPU-only BC1 path before relying on this for a release build.
 *
 * <h2>What is and isn't verified here</h2>
 * The compute dispatch / SSBO readback pattern below (image load/store
 * binding, {@code GL43.glDispatchCompute}, {@code GL43.glMemoryBarrier},
 * SSBO map-and-copy readback) follows standard, well-established OpenGL
 * 4.3 compute shader usage and LWJGL3's direct bindings for it -- this is
 * stable, long-standing API, not project-specific internals, so
 * confidence here is high. What is <em>not</em> independently verified is
 * the shader's numerical output quality (no way to render/inspect
 * compressed textures from this environment) and the exact byte-for-byte
 * compatibility of the packed block layout with what
 * {@code glCompressedTexImage2D} expects on every driver -- BC1's
 * "color0/color1/indices" byte order is a fixed, unambiguous part of the
 * S3TC spec, but a mismatched endianness assumption in the packing would
 * silently produce corrupted-looking (not crashing) textures. Test against
 * real hardware before trusting this in a release build.
 */
public final class Bc1ComputeEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/Bc1ComputeEncoder");
    private static final String SHADER_RESOURCE = "/assets/tessera/shaders/compute/bc1_encode.comp.glsl";

    private static volatile int cachedProgram = -1;

    private Bc1ComputeEncoder() {
    }

    public record EncodedBlocks(ByteBuffer packedBlocks, int blocksWide, int blocksHigh) {
    }

    /**
     * Encodes {@code sourceRgba8} (tightly packed, row-major RGBA8, no
     * padding) into BC1 blocks via the compute shader. Returns
     * {@link Optional#empty()} if compute-shader support is unavailable,
     * shader compilation fails, or any GL error occurs -- callers should
     * fall back to {@link CompressionPipeline}'s CPU path in that case,
     * exactly as they already do for a missing/failed native bridge.
     *
     * <p>{@code width}/{@code height} need not be multiples of 4; texels
     * beyond the source image's bounds within a boundary block are
     * undefined per the shader's {@code imageLoad} calls at those
     * coordinates -- <strong>UNVERIFIED:</strong> whether GLSL's
     * {@code imageLoad} clamps, wraps, or returns zero for out-of-bounds
     * coordinates on an {@code image2D} depends on the image's storage
     * allocation; the caller-side texture allocation below deliberately
     * allocates the full 4-aligned size and leaves the padding region
     * zero-initialized (GL's default for newly allocated texture storage)
     * specifically so out-of-bounds reads within a boundary block return
     * defined (zero) data rather than truly undefined values -- confirm
     * this assumption holds on your target driver before relying on it,
     * since it is inferred from general GL texture-storage semantics, not
     * confirmed against a specific driver's actual behavior.
     */
    public static Optional<EncodedBlocks> encode(ByteBuffer sourceRgba8, int width, int height) {
        if (!Bc1ComputeSupport.isSupported()) {
            return Optional.empty();
        }

        int program = tessera$ensureProgram();
        if (program < 0) {
            return Optional.empty();
        }

        int alignedWidth = (width + 3) & ~3;
        int alignedHeight = (height + 3) & ~3;
        int blocksWide = alignedWidth / 4;
        int blocksHigh = alignedHeight / 4;

        int sourceTexture = -1;
        int outputBuffer = -1;
        try {
            sourceTexture = tessera$createSourceTexture(sourceRgba8, width, height, alignedWidth, alignedHeight);
            outputBuffer = tessera$createOutputBuffer(blocksWide * blocksHigh);

            GL43.glUseProgram(program);
            GL43.glBindImageTexture(0, sourceTexture, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA8);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, outputBuffer);

            int blocksPerRowUniform = GL43.glGetUniformLocation(program, "blocksPerRow");
            GL43.glUniform1i(blocksPerRowUniform, blocksWide);

            // Workgroup size is 8x8 texels = one 4x4 block per invocation
            // within an 8x8-texel (2x2-block) local group -- see the
            // shader's own local_size_x/y declaration. Dispatch groups
            // therefore cover 8 blocks per dimension per group.
            int groupsX = (blocksWide + 7) / 8;
            int groupsY = (blocksHigh + 7) / 8;
            GL43.glDispatchCompute(groupsX, groupsY, 1);
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            int glError = GL11.glGetError();
            if (glError != GL11.GL_NO_ERROR) {
                LOGGER.warn("GL error {} during BC1 compute dispatch; falling back to CPU compression.", glError);
                return Optional.empty();
            }

            ByteBuffer packed = tessera$readBackBuffer(outputBuffer, blocksWide * blocksHigh * 8);
            return Optional.of(new EncodedBlocks(packed, blocksWide, blocksHigh));
        } catch (RuntimeException e) {
            LOGGER.warn("BC1 compute encode failed; falling back to CPU compression.", e);
            return Optional.empty();
        } finally {
            if (sourceTexture >= 0) {
                GL11.glDeleteTextures(sourceTexture);
            }
            if (outputBuffer >= 0) {
                GL15.glDeleteBuffers(outputBuffer);
            }
            GL43.glUseProgram(0);
        }
    }

    private static int tessera$createSourceTexture(ByteBuffer rgba8, int width, int height, int alignedWidth, int alignedHeight) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // Allocate the full 4-aligned size (zero-initialized by GL for a
        // NULL-data glTexImage2D call), then upload only the real
        // width x height region -- leaves boundary-block padding texels
        // at zero rather than genuinely undefined. See this method's
        // caller-side doc comment for why this matters.
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA8, alignedWidth, alignedHeight, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba8);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    private static int tessera$createOutputBuffer(int blockCount) {
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        // 8 bytes per BC1 block (uvec2 in the shader's SSBO).
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) blockCount * 8L, GL15.GL_STREAM_READ);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return buffer;
    }

    /**
     * <strong>UNVERIFIED:</strong> the specific LWJGL version-class each
     * GL function is called through below (e.g. {@code GL15.glBindBuffer}
     * vs {@code GL43.glBindBuffer}) was corrected once already during
     * review (glBindBuffer/glUnmapBuffer moved from GL43 to GL15, their
     * actual introducing version) but has not been compiled against a real
     * LWJGL3 classpath from this environment. LWJGL's version classes are
     * cumulative (GL43 exposes GL15's functions too via inheritance in
     * most LWJGL versions), so a leftover GL43-prefixed call to an
     * earlier-introduced function would likely still compile -- but
     * matching the function's actual introducing version class is the
     * correct, precise style this project already uses elsewhere
     * (GL11/GL13 called precisely rather than blanket GL13/GL43
     * everywhere). Double-check every GL##.func call below against your
     * IDE's autocomplete before compiling.
     */
    private static ByteBuffer tessera$readBackBuffer(int buffer, int byteSize) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        ByteBuffer mapped = GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, byteSize, GL30.GL_MAP_READ_BIT);
        ByteBuffer copy = ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder());
        assert mapped != null;
        ByteBuffer sourceView = mapped.duplicate().order(ByteOrder.nativeOrder());
        copy.put(sourceView);
        copy.rewind();
        GL15.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return copy;
    }

    private static synchronized int tessera$ensureProgram() {
        if (cachedProgram >= 0) {
            return cachedProgram;
        }

        String source = tessera$loadShaderSource();
        if (source == null) {
            cachedProgram = -2;
            return -1;
        }

        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, source);
        GL43.glCompileShader(shader);
        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("BC1 compute shader compilation failed: {}", GL43.glGetShaderInfoLog(shader));
            GL43.glDeleteShader(shader);
            cachedProgram = -2;
            return -1;
        }

        int program = GL43.glCreateProgram();
        GL43.glAttachShader(program, shader);
        GL43.glLinkProgram(program);
        GL43.glDeleteShader(shader);

        if (GL43.glGetProgrami(program, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("BC1 compute program link failed: {}", GL43.glGetProgramInfoLog(program));
            GL43.glDeleteProgram(program);
            cachedProgram = -2;
            return -1;
        }

        cachedProgram = program;
        return program;
    }

    /**
     * <strong>UNVERIFIED:</strong> resource loading via
     * {@code Bc1ComputeEncoder.class.getResourceAsStream(...)} assumes
     * {@code bc1_encode.comp.glsl} is packaged on the mod's classpath at
     * {@code /assets/tessera/shaders/compute/bc1_encode.comp.glsl} --
     * confirm this matches wherever the shader file actually ends up
     * relative to your build's resources root (typically
     * {@code src/main/resources/assets/tessera/shaders/compute/} for a
     * standard NeoForge Gradle layout, which should map to this classpath
     * location, but verify against your actual build output).
     */
    private static String tessera$loadShaderSource() {
        try (var stream = Bc1ComputeEncoder.class.getResourceAsStream(SHADER_RESOURCE)) {
            if (stream == null) {
                LOGGER.error("BC1 compute shader resource not found at {}", SHADER_RESOURCE);
                return null;
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to read BC1 compute shader resource.", e);
            return null;
        }
    }
}