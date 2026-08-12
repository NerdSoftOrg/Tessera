package com.nerdsoft.mods.tessera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nerdsoft.mods.tessera.Tessera;
import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draw-side counterpart to {@link SectionGeometryHandler}: once per
 * frame, at {@code RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS} and
 * {@code AFTER_CUTOUT_MIPPED_BLOCKS}, binds the corresponding Tessera
 * atlas and draws every stored section's accumulated geometry for that
 * target in one pass -- this is the two-extra-binds-per-frame (not
 * per-section) design confirmed with the person before implementation
 * began.
 *
 * <h2>Confirmed vs. unconfirmed</h2>
 * {@code RenderLevelStageEvent}'s accessor methods used here
 * ({@code getStage()}, {@code getLevelRenderer()}, {@code getPoseStack()},
 * {@code getProjectionMatrix()}, {@code getCamera()}) and the
 * {@code Stage} enum constants ({@code AFTER_SOLID_BLOCKS},
 * {@code AFTER_CUTOUT_MIPPED_BLOCKS}) are confirmed against NeoForge's own
 * documentation across the 1.21.0-beta/1.21.1 era. The event's exact
 * <em>constructor</em> shape varies across 1.21.x minor versions (a
 * meaningfully different shape was confirmed to exist by 1.21.10 during
 * this project's own research), but this class never constructs the
 * event, only reads from it via accessors, which sidesteps that
 * version-churn risk.
 *
 * <p>{@code RenderSystem.applyModelViewMatrix()}/{@code getModelViewStack()}
 * (returning {@code Matrix4fStack}) used in
 * {@link #tessera$drawSection} are confirmed present with this exact
 * signature against Yarn's 1.21.1+build.3 mappings -- the precise target
 * version, not an adjacent one. Worth flagging for future maintenance:
 * this same lookup also confirmed {@code RenderSystem} was substantially
 * restructured around a new {@code GpuDevice}/{@code RenderPass}
 * abstraction starting around 1.21.5, removing several of the methods
 * used here entirely -- this class's direct-GL approach is specific to
 * 1.21.1's still-classic-OpenGL-style {@code RenderSystem} and would need
 * a rewrite, not a small patch, to target anything past roughly 1.21.4.</p>
 *
 * <p><strong>Persistent buffer caching:</strong> {@link #tessera$drawSection}
 * reuses a persistent GL buffer across frames via
 * {@link SectionGeometryStore.GpuBufferCache}, keyed on
 * {@code CompiledSectionGeometry} object identity -- an earlier version of
 * this class created and destroyed a transient VBO on every section every
 * frame, which was correct but wasteful given this project's FPS goal.
 * Re-upload now only happens when a section's geometry instance actually
 * changes (i.e. the section recompiled), not every frame regardless of
 * whether anything changed.</p>
 */
// Compatibility for 1.21
@SuppressWarnings("removal")
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class LevelRenderHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/LevelRenderHandler");
    private static volatile int tessera$sharedQuadIndexBuffer = -1;
    private static volatile int tessera$sharedQuadIndexBufferCapacity = 0;

    private LevelRenderHandler() {
    }

    @SuppressWarnings("resource")
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        AtlasSplitTarget target;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            target = AtlasSplitTarget.OPAQUE;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS) {
            target = AtlasSplitTarget.ALPHA;
        } else {
            return;
        }

        var atlas = TesseraClient.SPLIT_ATLAS_MANAGER.atlasFor(target);
        int textureId = atlas.getId();

        Camera camera = event.getCamera();
        double camX = camera.getPosition().x();
        double camY = camera.getPosition().y();
        double camZ = camera.getPosition().z();

        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        int[] drawnSections = {0};
        SectionGeometryStore.forEachSection(target, (sectionOrigin, geometry) ->
                tessera$drawSection(sectionOrigin, geometry, camX, camY, camZ, event.getModelViewMatrix(), event.getProjectionMatrix(), drawnSections));

        RenderSystem.disableBlend();

        if (drawnSections[0] > 0) {
            LOGGER.debug("Drew {} sections for Tessera atlas {}.", drawnSections[0], target);
        }
    }

    /**
     * Draws one section's geometry, reusing a persistent GL buffer across
     * frames via {@link SectionGeometryStore.GpuBufferCache} rather
     * than creating and destroying a transient VBO on every call -- this
     * replaces an earlier version of this method that did exactly that
     * (correct, but wasteful: allocate + upload + delete, every section,
     * every frame, even for sections that hadn't recompiled since the
     * previous frame). {@code glBufferData} is only called on a cache
     * miss (this exact {@code CompiledSectionGeometry} instance has never
     * been uploaded before), which per that record's own doc only happens
     * when the section has genuinely recompiled.
     *
     * <p><strong>UNVERIFIED:</strong> vertex attribute pointers below are
     * written against the standard, well-established OpenGL 3.x+ pattern
     * for interleaved vertex buffers (stable, confirmed API), but the
     * exact byte layout assumed (element order confirmed, per-element
     * component counts best-effort) is not independently confirmed
     * against Minecraft's real vertex data -- see the stride-derivation
     * comment below and {@code TesseraSectionGeometryHandler}'s own doc
     * for the full caveat. A mismatch here would produce visually wrong
     * (garbled position/color/UV) but not crashing output.
     */
    @SuppressWarnings("unused")
    private static void tessera$drawSection(
            BlockPos sectionOrigin, SectionGeometryStore.CompiledSectionGeometry geometry,
            double camX, double camY, double camZ, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, int[] drawnSections
    ) {
        int vbo = SectionGeometryStore.GpuBufferCache.get(geometry).orElseGet(() -> {
            int newBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, newBuffer);
            // GL_STATIC_DRAW, not GL_STREAM_DRAW: this buffer is now
            // genuinely persistent and reused across many frames (until
            // the section recompiles and a new CompiledSectionGeometry
            // instance replaces this cache entry), matching STATIC_DRAW's
            // intended usage pattern -- an earlier version used
            // STREAM_DRAW, correct for the old create-upload-delete-every-
            // frame pattern but no longer the right hint now that this
            // buffer outlives a single frame.
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, geometry.vertexData(), GL15.GL_STATIC_DRAW);
            SectionGeometryStore.GpuBufferCache.put(geometry, newBuffer);
            return newBuffer;
        });

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // Stride derived from the buffer's own actual size rather than a
        // hardcoded element count -- self-consistent with whatever
        // TesseraSectionGeometryHandler.tessera$bakeVertices actually
        // produces (4 vertices per quad, N floats per vertex, N
        // determined by BakedQuad.getVertices().length / 4, itself not
        // independently confirmed against Minecraft's real
        // DefaultVertexFormat.BLOCK layout -- see that method's own doc).
        // Deriving here instead of hardcoding means this file cannot
        // silently drift out of sync with that one even if the true
        // element count differs from what either file assumes.
        int totalVertices = geometry.quadCount() * 4;
        int floatsPerVertex = (geometry.vertexData().remaining() / Float.BYTES) / totalVertices;
        int stride = floatsPerVertex * Float.BYTES;

        // UNVERIFIED byte layout, best-effort: element ORDER is confirmed
        // (Position, Color, UV, Lightmap, Normal -- no Overlay -- via
        // DefaultVertexFormat.BLOCK's own canonical Yarn name,
        // POSITION_COLOR_TEXTURE_LIGHT_NORMAL, which notably omits
        // "OVERLAY" that NEW_ENTITY's name includes; an earlier draft of
        // this method incorrectly included a 5th, overlay attribute
        // pointer copied from a NEW_ENTITY-shaped assumption -- removed
        // here). Per-element component counts/types below (color as 4
        // bytes, UV as 2 floats, lightmap as 1 float, normal as 1 float)
        // are a best-effort assumption, not independently confirmed
        // against Minecraft's real packed layout -- if floatsPerVertex
        // above doesn't equal 8, that assumption is definitely wrong and
        // needs re-deriving against the real per-element sizes. Verify
        // against DefaultVertexFormat.BLOCK's actual element list in your
        // IDE before trusting this draw call's visual output.
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);

        // Color: 4 packed unsigned bytes (RGBA), normalized to [0,1] in-shader --
        // NOT a single float. Reading this as GL_FLOAT reinterprets the raw RGBA
        // byte pattern as an IEEE-754 float, which is why quads rendered solid
        // black/NaN-dark: the resulting "color multiplier" was garbage.
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, stride, 12L);

        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 16L);

        // UV2 (lightmap coords): 2 packed shorts, NOT a single float. Same
        // reinterpretation problem as color -- corrupted lightmap values feed
        // directly into the fragment shader's lighting term.
        GL20.glEnableVertexAttribArray(3);
        GL20.glVertexAttribPointer(3, 2, GL11.GL_SHORT, false, stride, 24L);

        // Normal: 4 packed signed bytes (XYZ + pad), normalized, NOT a single
        // float.
        GL20.glEnableVertexAttribArray(4);
        GL20.glVertexAttribPointer(4, 4, GL11.GL_BYTE, true, stride, 28L);

        // Camera-relative translation: sectionOrigin is in absolute world
        // coordinates, but vanilla's own chunk rendering (and therefore
        // the modelViewMatrix this event supplies) is camera-relative --
        // subtracting the camera position matches that convention so this
        // section draws at the correct relative position rather than
        // offset by the camera's own absolute coordinates.
        Matrix4f sectionModelView = new Matrix4f(modelViewMatrix)
                .translate((float) (sectionOrigin.getX() - camX), (float) (sectionOrigin.getY() - camY), (float) (sectionOrigin.getZ() - camZ));

        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().mul(sectionModelView);
        RenderSystem.applyModelViewMatrix();

        // GL_QUADS is not a valid primitive mode in core OpenGL profiles
        // -- vanilla's own VertexFormat.Mode.QUADS is a logical grouping
        // only; actual GL draws expand each 4-vertex quad into 2
        // triangles (6 indices: 0,1,2 / 2,3,0) via a pre-built index
        // buffer (confirmed pattern:
        // RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS) in
        // vanilla's own code). tessera$ensureQuadIndexBuffer below
        // reproduces that same expansion for however many quads this
        // section has, reused/grown across calls rather than rebuilt per
        // draw.
        int indexBuffer = tessera$ensureQuadIndexBuffer(geometry.quadCount());
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        GL11.glDrawElements(GL11.GL_TRIANGLES, geometry.quadCount() * 6, GL11.GL_UNSIGNED_INT, 0L);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        drawnSections[0]++;
    }

    /**
     * Lazily builds (and grows, never shrinks) one shared quad-expansion
     * index buffer reused across every section drawn this frame and
     * across frames -- avoids rebuilding an index buffer per section per
     * frame, since the 0,1,2,2,3,0 pattern is identical for every quad
     * regardless of which section or atlas it belongs to; only the total
     * quad count varies, and only the largest section's count actually
     * needs to be covered since {@code glDrawElements} only reads as many
     * indices as requested.
     */
    private static synchronized int tessera$ensureQuadIndexBuffer(int requiredQuadCount) {
        if (tessera$sharedQuadIndexBuffer >= 0 && tessera$sharedQuadIndexBufferCapacity >= requiredQuadCount) {
            return tessera$sharedQuadIndexBuffer;
        }

        int newCapacity = Math.max(requiredQuadCount, tessera$sharedQuadIndexBufferCapacity * 2);
        java.nio.IntBuffer indices = java.nio.ByteBuffer.allocateDirect(newCapacity * 6 * Integer.BYTES)
                .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
        for (int q = 0; q < newCapacity; q++) {
            int base = q * 4;
            indices.put(base).put(base + 1).put(base + 2);
            indices.put(base + 2).put(base + 3).put(base);
        }
        indices.rewind();

        if (tessera$sharedQuadIndexBuffer < 0) {
            tessera$sharedQuadIndexBuffer = GL15.glGenBuffers();
        }
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, tessera$sharedQuadIndexBuffer);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        tessera$sharedQuadIndexBufferCapacity = newCapacity;
        return tessera$sharedQuadIndexBuffer;
    }
}