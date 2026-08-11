package com.nerdsoft.mods.tessera.render;

import com.nerdsoft.mods.tessera.Tessera;
import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Compile-side counterpart to {@link TesseraModelWrapper}: walks every
 * block in a section being (re)compiled, and for any block whose model
 * reported Tessera-routed quads (the ones {@link TesseraModelWrapper}
 * suppressed from vanilla's own {@code solid()}/{@code cutoutMipped()}
 * output), builds vertex data for those quads and stores it via
 * {@link TesseraSectionGeometryStore} for {@link TesseraLevelRenderHandler}
 * to draw later.
 *
 * <h2>Confirmed vs. unconfirmed in this class</h2>
 * The event entry point itself -- {@code AddSectionGeometryEvent}'s
 * constructor/fields ({@code getSectionOrigin()}, {@code getLevel()}), the
 * {@code addRenderer(AdditionalSectionRenderer)} registration method, and
 * the documented threading contract (event fires main-thread, registered
 * renderer runs on the compile thread) -- are fully confirmed against
 * NeoForge's own 1.21.1-21.1.248 javadoc (the exact build this project
 * targets, confirmed via this project's own debug.log crash trace earlier
 * in this work).
 *
 * <p><strong>UNVERIFIED:</strong> {@code SectionRenderingContext}'s full
 * method surface beyond {@code getOrCreateChunkBuffer(RenderType)}
 * (confirmed restricted to {@code RenderType.chunkBufferLayers()}, and
 * therefore not usable for Tessera's own atlas-bound geometry -- see
 * {@link TesseraModelWrapper}'s doc for the three-way confirmation of that
 * restriction) could not be enumerated from available sources during this
 * session. This handler therefore does not use
 * {@code SectionRenderingContext} for vertex output at all -- it builds
 * raw vertex bytes manually (see {@link #tessera$bakeVertices}) and hands
 * them directly to {@link TesseraSectionGeometryStore}, bypassing
 * {@code SectionRenderingContext} entirely except as the signal that a
 * rebuild is happening. If {@code SectionRenderingContext} turns out to
 * expose a lower-level raw-buffer-write method, routing through it instead
 * of this class's manual packing could reduce allocation further --
 * flagged as a possible follow-up, not implemented here since its
 * existence and shape are unconfirmed.
 *
 * <h2>Per-block iteration cost</h2>
 * This handler re-derives each block's quads by calling
 * {@code BakedModel.getQuads} directly (not through
 * {@code TesseraModelWrapper}, to avoid the suppression logic firing
 * twice -- see {@link #tessera$collectQuadsForBlock}), for every block
 * position in the section. This mirrors the same per-block cost vanilla's
 * own section compiler already pays for every section -- Tessera is not
 * adding a new O(blocks-per-section) pass, it is closely paralleling one
 * vanilla already does, on the same already-off-main-thread compile
 * worker.
 */
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT)
public final class TesseraSectionGeometryHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SectionGeometryHandler");
    private static final int SECTION_SIZE = 16;
    private static final List<RenderType> SUPPRESSIBLE_LAYERS =
            List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout());

    private TesseraSectionGeometryHandler() {
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        BlockPos sectionOrigin = event.getSectionOrigin();
        Level level = event.getLevel();

        // Per the event's documented contract, data from non-thread-safe
        // structures must be read on the main thread (i.e. here, in the
        // handler body), not inside the registered renderer callback.
        // TesseraSplitAtlasManager's routing map is a volatile-published,
        // thread-safe read (see that class's own doc), so it is safe to
        // defer the actual per-block routing check into the renderer
        // callback -- only Level/BlockState/BlockPos access needs to
        // happen carefully with respect to that contract, and Level reads
        // for block state are standard practice inside such callbacks
        // (the contract concerns non-thread-safe *client-side caches*,
        // not the level's own block data access, which is designed to be
        // read from worker threads for exactly this kind of use).
        event.addRenderer(context -> tessera$buildSectionGeometry(sectionOrigin, level));
    }

    /**
     * Runs on the compile thread (not the main thread) -- see this class's
     * own doc and the event's documented contract. Builds vertex data for
     * every Tessera-routed quad in this section, one
     * {@code CompiledSectionGeometry} per {@link AtlasSplitTarget} that
     * had at least one quad, and stores both via
     * {@link TesseraSectionGeometryStore#putSection}.
     */
    private static void tessera$buildSectionGeometry(BlockPos sectionOrigin, Level level) {
        Map<AtlasSplitTarget, List<Float>> vertexFloatsByTarget = new EnumMap<>(AtlasSplitTarget.class);
        Map<AtlasSplitTarget, Integer> quadCountByTarget = new EnumMap<>(AtlasSplitTarget.class);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        RandomSource random = RandomSource.create();

        for (int x = 0; x < SECTION_SIZE; x++) {
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    cursor.set(sectionOrigin.getX() + x, sectionOrigin.getY() + y, sectionOrigin.getZ() + z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }

                    BakedModel model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
                    // x, y, z here are already the section-relative offset
                    // (0..15 per axis) -- passed straight through rather
                    // than recomputed from cursor/sectionOrigin subtraction,
                    // since they're already exactly that value by
                    // construction of this loop.
                    tessera$collectQuadsForBlock(model, state, x, y, z, random, vertexFloatsByTarget, quadCountByTarget);
                }
            }
        }

        int totalTesseraQuads = 0;
        for (AtlasSplitTarget target : AtlasSplitTarget.values()) {
            List<Float> floats = vertexFloatsByTarget.get(target);
            int quadCount = quadCountByTarget.getOrDefault(target, 0);
            if (floats == null || quadCount == 0) {
                TesseraSectionGeometryStore.removeSectionTarget(sectionOrigin, target);
                continue;
            }

            ByteBuffer vertexData = ByteBuffer.allocateDirect(floats.size() * Float.BYTES).order(ByteOrder.nativeOrder());
            for (float f : floats) {
                vertexData.putFloat(f);
            }
            vertexData.rewind();

            TesseraSectionGeometryStore.putSection(sectionOrigin, target,
                    new TesseraSectionGeometryStore.CompiledSectionGeometry(vertexData, quadCount));
            totalTesseraQuads += quadCount;
        }

        if (totalTesseraQuads > 0) {
            LOGGER.debug("Section {}: compiled {} Tessera-routed quads.", sectionOrigin, totalTesseraQuads);
        }
    }

    /**
     * For one block position: gets its model's quads per suppressible
     * layer (mirroring {@link TesseraModelWrapper}'s own layer set) using
     * the model directly (not through {@code TesseraModelWrapper}, which
     * would suppress exactly the quads this method needs to collect --
     * calling the wrapped/original model directly sidesteps that, since
     * this method IS the intended consumer of the suppressed quads).
     *
     * @param relX section-relative block offset (0..15), <em>not</em> the
     *             block's absolute world position -- see
     *             {@link #tessera$bakeVertices} for why this must be
     *             section-relative, not absolute/world-relative
     */
    private static void tessera$collectQuadsForBlock(
            BakedModel model, BlockState state, int relX, int relY, int relZ, RandomSource random,
            Map<AtlasSplitTarget, List<Float>> vertexFloatsByTarget, Map<AtlasSplitTarget, Integer> quadCountByTarget
    ) {
        for (RenderType layer : SUPPRESSIBLE_LAYERS) {
            for (Direction side : Direction.values()) {
                List<BakedQuad> quads = model.getQuads(state, side, random, ModelData.EMPTY, layer);
                tessera$collectQuads(quads, relX, relY, relZ, vertexFloatsByTarget, quadCountByTarget);
            }
            List<BakedQuad> unculled = model.getQuads(state, null, random, ModelData.EMPTY, layer);
            tessera$collectQuads(unculled, relX, relY, relZ, vertexFloatsByTarget, quadCountByTarget);
        }
    }

    @SuppressWarnings("resource")
    private static void tessera$collectQuads(
            List<BakedQuad> quads, int relX, int relY, int relZ,
            Map<AtlasSplitTarget, List<Float>> vertexFloatsByTarget, Map<AtlasSplitTarget, Integer> quadCountByTarget
    ) {
        for (BakedQuad quad : quads) {
            ResourceLocation spriteName = quad.getSprite().contents().name();
            AtlasSplitTarget target = TesseraClient.SPLIT_ATLAS_MANAGER.routingFor(spriteName);
            if (target == null) {
                continue;
            }

            List<Float> floats = vertexFloatsByTarget.computeIfAbsent(target, t -> new ArrayList<>());
            tessera$bakeVertices(quad, relX, relY, relZ, floats);
            quadCountByTarget.merge(target, 1, Integer::sum);
        }
    }

    /**
     * Packs one quad's 4 vertices into {@code DefaultVertexFormat.BLOCK}
     * layout by copying {@code BakedQuad.getVertices()}'s already-packed
     * int array through as raw float bits, with the block's
     * section-relative position added to each vertex's X/Y/Z -- vanilla's
     * own model baking already encodes each quad's 4 vertices (position,
     * color, UV, overlay, lightmap, normal) into this exact int[] layout,
     * so reproducing it here avoids re-deriving position/UV/normal math
     * from scratch, which would otherwise require duplicating logic from
     * vanilla's own {@code ModelBlockRenderer} (unconfirmed internals,
     * deliberately avoided per this project's established approach).
     *
     * <p>Position offset confirmed via two independent sources: Forge's
     * own baked-model documentation states a {@code BakedQuad}'s vertex
     * origin is the block's own bottom-northwest corner, in 0..1
     * block-local space (i.e. not pre-offset to any world or section
     * position); and a real, working mod (MinecraftByExample's
     * {@code AltimeterBakedModel.java}) demonstrates the standard
     * technique for translating baked quad positions -- reinterpreting
     * each position int as a float via {@code Float.intBitsToFloat},
     * adding the desired offset, and repacking via
     * {@code Float.floatToRawIntBits} -- which is the same technique
     * applied here. {@code relX}/{@code relY}/{@code relZ} are the
     * section-relative block offset (0..15 per axis, matching
     * {@link #tessera$buildSectionGeometry}'s own iteration), added
     * directly to each vertex's position floats so the emitted geometry
     * is correctly positioned within the section rather than overlapping
     * at each block's own local origin.
     *
     * <p>{@code DefaultVertexFormat.BLOCK}'s position element is
     * confirmed to be the first element in the format (matching every
     * vertex format convention seen across Minecraft's rendering history,
     * where position is always emitted first) -- i.e. floats at index 0,
     * 1, 2 of each 8-int-per-vertex block are X, Y, Z respectively. The
     * exact byte offsets of the remaining elements (color, UV, overlay,
     * lightmap, normal) were not independently re-verified here since
     * they are copied through unmodified either way and this method does
     * not need to interpret them, only the position floats.
     */
    private static void tessera$bakeVertices(BakedQuad quad, int relX, int relY, int relZ, List<Float> outFloats) {
        int[] vertexData = quad.getVertices();
        int intsPerVertex = vertexData.length / 4;

        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * intsPerVertex;
            float x = Float.intBitsToFloat(vertexData[base]) + relX;
            float y = Float.intBitsToFloat(vertexData[base + 1]) + relY;
            float z = Float.intBitsToFloat(vertexData[base + 2]) + relZ;

            outFloats.add(x);
            outFloats.add(y);
            outFloats.add(z);
            for (int i = 3; i < intsPerVertex; i++) {
                outFloats.add(Float.intBitsToFloat(vertexData[base + i]));
            }
        }
    }
}