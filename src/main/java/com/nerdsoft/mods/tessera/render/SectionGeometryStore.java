package com.nerdsoft.mods.tessera.render;

import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns Tessera's own compiled per-section geometry -- the counterpart to
 * vanilla's own {@code RenderChunk}-held {@code VertexBuffer}s, but for the
 * two Tessera render types that cannot participate in vanilla's own
 * {@code chunkBufferLayers()} compile/draw cycle (see
 * {@link ModelWrapper}'s doc for why not).
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link SectionGeometryHandler} populates one entry per
 *       section per {@link AtlasSplitTarget}, whenever
 *       {@code AddSectionGeometryEvent} fires for that section -- i.e. on
 *       initial chunk load and on every subsequent rebuild triggered by a
 *       block change in that section, exactly mirroring vanilla's own
 *       section recompile cadence. No independent dirty-tracking is
 *       implemented or needed: Tessera's geometry goes stale at exactly
 *       the same moments vanilla's own compiled buffers do, and is rebuilt
 *       at exactly the same moments, for free, by riding on this event.</li>
 *   <li>{@link LevelRenderHandler} reads (never writes) during
 *       {@code RenderLevelStageEvent}, once per frame per stage.</li>
 *   <li>Entries are removed when a section is known to be gone (unloaded)
 *       -- see {@link #removeSection}. Not currently wired to any event:
 *       {@code net.neoforged.neoforge.event.level.ChunkEvent.Unload} is a
 *       confirmed, real event for chunk-column unload, but it fires per
 *       {@code ChunkAccess} (a full 16-section column), not per
 *       render-section, and translating that to this store's per-section
 *       {@code SectionKey}s (looping y from the column's min to max
 *       section and calling {@link #removeSection} for each) is
 *       straightforward but not implemented in this pass. Until wired,
 *       unloaded sections' entries are simply retained, at the cost of an
 *       unbounded, slowly-growing map for a long play session with heavy
 *       chunk churn -- flagging this as a known, currently-unaddressed
 *       memory-growth gap rather than leaving it silently unmentioned.</li>
 * </ul>
 */
public final class SectionGeometryStore {

    private SectionGeometryStore() {
    }

    /**
     * One compiled buffer's worth of geometry for one section, one atlas
     * target. {@code vertexData} is already-baked vertex bytes (position,
     * color, uv, lightmap, normal -- see
     * {@code TesseraSectionGeometryHandler}'s own doc for why there is no
     * overlay element, unlike {@code NEW_ENTITY} format), ready to upload
     * to a GL buffer as-is. {@code quadCount} is tracked separately since
     * it determines the index buffer draw count.
     *
     * <p>Each call to {@link #putSection} creates a new instance of this
     * record -- object identity (not content equality) is therefore a
     * valid, cheap signal for "this section's geometry actually changed
     * since last frame," which {@link GpuBufferCache} relies on to avoid
     * re-uploading a GL buffer for a section that hasn't recompiled.
     */
    public record CompiledSectionGeometry(ByteBuffer vertexData, int quadCount) {
    }

    /**
     * Render-thread-only cache mapping a specific
     * {@link CompiledSectionGeometry} instance to the persistent GL
     * buffer object already holding its uploaded contents. Used by
     * {@link LevelRenderHandler} to avoid creating and destroying
     * a transient VBO every section every frame -- an earlier version of
     * that class did exactly that, which is correct but wasteful
     * (allocate + upload + delete, every section, every frame, even for
     * sections whose geometry has not changed since the last frame).
     *
     * <p>{@link IdentityHashMap} is used deliberately: this cache keys on
     * "is this the exact same compiled-geometry object as last frame",
     * not "does this geometry have equal content to some other entry" --
     * a {@code CompiledSectionGeometry} record technically has a
     * generated {@code equals()}, but relying on it here would mean
     * comparing (and therefore reading through) potentially large
     * {@code ByteBuffer} contents every frame just to decide whether a
     * re-upload is needed, which defeats the point of caching. Object
     * identity is the correct and cheap check: a new instance only ever
     * exists because {@link #putSection} was called again for that
     * section, which only happens on an actual recompile.
     *
     * <p>Entries for sections removed via {@link #removeSectionTarget}/
     * {@link #removeSection} are not automatically evicted here -- see
     * {@link #removeSectionTarget}'s own note on this. A stale cache
     * entry only wastes a small amount of render-thread-owned GPU memory
     * (one small VBO) until this gap is closed alongside this class's
     * already-documented section-unload gap; it does not cause incorrect
     * rendering, since {@link LevelRenderHandler} only ever looks
     * up a cache entry for a {@code CompiledSectionGeometry} it is
     * actively about to draw.
     */
    public static final class GpuBufferCache {
        private static final Map<CompiledSectionGeometry, Integer> BUFFERS = new java.util.IdentityHashMap<>();

        private GpuBufferCache() {
        }

        /**
         * Returns the cached GL buffer id for this exact geometry
         * instance, or empty if nothing is cached yet (caller should
         * create, upload, and register one via {@link #put}).
         */
        public static java.util.OptionalInt get(CompiledSectionGeometry geometry) {
            Integer id = BUFFERS.get(geometry);
            return id == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(id);
        }

        public static void put(CompiledSectionGeometry geometry, int glBufferId) {
            BUFFERS.put(geometry, glBufferId);
        }
    }

    private record SectionKey(int x, int y, int z) {
        static SectionKey of(BlockPos sectionOrigin) {
            return new SectionKey(sectionOrigin.getX(), sectionOrigin.getY(), sectionOrigin.getZ());
        }
    }

    private static final Map<SectionKey, Map<AtlasSplitTarget, CompiledSectionGeometry>> SECTIONS = new ConcurrentHashMap<>();

    /**
     * Called from {@link SectionGeometryHandler}'s
     * {@code AdditionalSectionRenderer} callback, which per
     * {@code AddSectionGeometryEvent}'s own documentation runs on
     * "the thread performing the rebuild, which will typically not be the
     * main thread" -- {@link ConcurrentHashMap} is used specifically to be
     * safe under that documented concurrent-write contract, since
     * different sections can compile concurrently on different worker
     * threads.
     */
    public static void putSection(BlockPos sectionOrigin, AtlasSplitTarget target, CompiledSectionGeometry geometry) {
        SECTIONS.computeIfAbsent(SectionKey.of(sectionOrigin), k -> new ConcurrentHashMap<>()).put(target, geometry);
    }

    /**
     * Removes a target's geometry for a section, e.g. when a recompile
     * determines that section no longer has any quads for that target
     * (all Tessera-routed blocks were removed/changed). Passing an empty
     * per-target map to {@link #putSection} instead of calling this is
     * equally valid -- {@link #forEachSection} skips empty entries either
     * way -- this method exists for callers that prefer explicit removal.
     */
    public static void removeSectionTarget(BlockPos sectionOrigin, AtlasSplitTarget target) {
        Map<AtlasSplitTarget, CompiledSectionGeometry> perTarget = SECTIONS.get(SectionKey.of(sectionOrigin));
        if (perTarget != null) {
            perTarget.remove(target);
        }
    }

    /**
     * See this class's lifecycle doc -- not currently called by anything.
     */
    @SuppressWarnings("unused")
    public static void removeSection(BlockPos sectionOrigin) {
        SECTIONS.remove(SectionKey.of(sectionOrigin));
    }

    /**
     * Render-thread iteration for {@link LevelRenderHandler}.
     * {@code action}'s first argument is the section origin reconstructed
     * from the internal key -- allocates one {@link BlockPos} per section
     * per draw call, which is a small, bounded per-frame allocation
     * (bounded by loaded-section count, not by anything scaling with
     * frame complexity) rather than a hot-path per-quad or per-vertex
     * allocation, consistent with this project's zero-hot-path-allocation
     * goal for anything that actually scales with scene complexity.
     */
    public static void forEachSection(AtlasSplitTarget target, java.util.function.BiConsumer<BlockPos, CompiledSectionGeometry> action) {
        for (Map.Entry<SectionKey, Map<AtlasSplitTarget, CompiledSectionGeometry>> entry : SECTIONS.entrySet()) {
            CompiledSectionGeometry geometry = entry.getValue().get(target);
            if (geometry != null && geometry.quadCount() > 0) {
                SectionKey key = entry.getKey();
                action.accept(new BlockPos(key.x(), key.y(), key.z()), geometry);
            }
        }
    }
}