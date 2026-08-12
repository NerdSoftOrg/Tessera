package com.nerdsoft.mods.tessera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nerdsoft.mods.tessera.Tessera;
import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
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
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class LevelRenderHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/LevelRenderHandler");
    private static volatile int tessera$sharedQuadIndexBuffer = -1;
    private static volatile int tessera$sharedQuadIndexBufferCapacity = 0;
    private static volatile int tessera$vao = -1;

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

        if (!TesseraClient.SPLIT_ATLAS_MANAGER.hasContent(target)) {
            return;
        }

        int storedSections = SectionGeometryStore.getSectionCount(target);
        if (storedSections == 0) {
            return;
        }

        var atlas = TesseraClient.SPLIT_ATLAS_MANAGER.atlasFor(target);
        int textureId = atlas.getId();

        Camera camera = event.getCamera();
        double camX = camera.getPosition().x();
        double camY = camera.getPosition().y();
        double camZ = camera.getPosition().z();

        RenderSystem.setShader(target == AtlasSplitTarget.OPAQUE
                ? GameRenderer::getRendertypeSolidShader
                : GameRenderer::getRendertypeCutoutMippedShader);
        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            RenderSystem.setupShaderLights(shader);
        }

        int[] drawnSections = {0};
        SectionGeometryStore.forEachSection(target, (sectionOrigin, geometry) ->
                tessera$drawSection(sectionOrigin, geometry, camX, camY, camZ, shader, drawnSections));

        RenderSystem.disableBlend();

        if (drawnSections[0] > 0) {
            LOGGER.info("[Tessera-Debug] Drew {} sections for Tessera atlas {}.", drawnSections[0], target);
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
     */
    @SuppressWarnings("unused")
    private static void tessera$drawSection(
            BlockPos sectionOrigin, SectionGeometryStore.CompiledSectionGeometry geometry,
            double camX, double camY, double camZ, ShaderInstance shader, int[] drawnSections
    ) {
        if (tessera$vao < 0) {
            tessera$vao = GL30.glGenVertexArrays();
        }
        GL30.glBindVertexArray(tessera$vao);

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

        int stride = 32;

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

        if (shader != null && shader.CHUNK_OFFSET != null) {
            shader.CHUNK_OFFSET.set(
                    (float) (sectionOrigin.getX() - camX),
                    (float) (sectionOrigin.getY() - camY),
                    (float) (sectionOrigin.getZ() - camZ));
            shader.apply();
        }

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

        GL20.glDisableVertexAttribArray(0);
        GL20.glDisableVertexAttribArray(1);
        GL20.glDisableVertexAttribArray(2);
        GL20.glDisableVertexAttribArray(3);
        GL20.glDisableVertexAttribArray(4);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

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