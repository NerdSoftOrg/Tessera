package com.nerdsoft.mods.tessera.render;

import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Suppresses a block model's own {@code solid()}/{@code cutoutMipped()}
 * quads whenever a quad references a sprite Tessera routed onto one of its
 * own atlases -- so vanilla's own chunk-compile walk (which iterates
 * exactly {@code RenderType.chunkBufferLayers()} and asks each block's
 * model for quads per layer, per the confirmed contract documented at
 * {@code docs.neoforged.net/docs/1.21.1/resources/client/models/bakedmodel})
 * gets nothing for that block/layer/quad. {@link TesseraSectionGeometryHandler}
 * is what actually draws that geometry instead, via
 * {@code AddSectionGeometryEvent} -- see that class's doc for why this
 * split is required and how the two halves stay in sync.
 *
 * <h2>Why this is the correct (and only confirmed-safe) suppression point</h2>
 * An earlier draft of Tessera's render integration tried to have
 * {@code getRenderTypes()} report an entirely new, Tessera-owned
 * {@code RenderType} for such blocks. That is confirmed, three times over
 * during this project's research (via {@code RegisterNamedRenderTypesEvent}'s
 * chunk-type restriction, {@code IBakedModelExtension.getRenderTypes}'s own
 * restriction to {@code chunkBufferLayers()}, and
 * {@code SectionRenderingContext.getOrCreateChunkBuffer}'s identical
 * restriction) to be impossible: nothing in vanilla or NeoForge will ever
 * compile geometry for a render type outside {@code chunkBufferLayers()}
 * during normal chunk section compilation. This class does not attempt
 * that. It works entirely within the closed set: {@code getRenderTypes()}
 * still only ever returns vanilla's own layers (inherited unchanged from
 * {@link BakedModelWrapper}), and {@code getQuads()} simply omits whatever
 * quads are Tessera-owned from what it returns for a suppressible layer.
 *
 * <h2>Partial-layer blocks</h2>
 * A block whose {@code solid()} quads are a <em>mix</em> of vanilla-atlas
 * and Tessera-atlas sprites (uncommon, but not impossible for a multi-part
 * model) is handled conservatively via quad-level filtering, not
 * whole-layer suppression -- vanilla-atlas quads on that same block/layer
 * still render normally through vanilla's own buffer. Only quads that are
 * Tessera-routed are removed from what {@code getQuads} returns.
 */
public final class TesseraModelWrapper extends BakedModelWrapper<BakedModel> {

    private static final Set<RenderType> SUPPRESSIBLE_LAYERS = Set.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout());

    public TesseraModelWrapper(BakedModel original) {
        super(original);
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(BlockState state, Direction side, @NotNull RandomSource rand, @NotNull ModelData extraData, RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, rand, extraData, renderType);
        if (renderType == null || !SUPPRESSIBLE_LAYERS.contains(renderType) || quads.isEmpty()) {
            return quads;
        }

        // Fast path: nothing to filter if none of this block's quads on
        // this layer reference a Tessera-routed sprite. Checked before
        // allocating a filtered list, since the overwhelming majority of
        // blocks in any given section are not Tessera-routed at all (only
        // static, non-dynamic sprites are split -- see AtlasSplitTarget).
        boolean anyTesseraRouted = false;
        for (BakedQuad quad : quads) {
            if (tessera$routingFor(quad) != null) {
                anyTesseraRouted = true;
                break;
            }
        }
        if (!anyTesseraRouted) {
            return quads;
        }

        List<BakedQuad> filtered = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            if (tessera$routingFor(quad) == null) {
                filtered.add(quad);
            }
        }
        return filtered;
    }

    @SuppressWarnings("resource")
    private AtlasSplitTarget tessera$routingFor(BakedQuad quad) {
        ResourceLocation spriteName = quad.getSprite().contents().name();
        return TesseraClient.SPLIT_ATLAS_MANAGER.routingFor(spriteName);
    }
}