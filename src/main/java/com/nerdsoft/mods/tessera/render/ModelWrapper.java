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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * gets nothing for that block/layer/quad. {@link SectionGeometryHandler}
 * is what actually draws that geometry instead, via
 * {@code AddSectionGeometryEvent} -- see that class's doc for why this
 * split is required and how the two halves stay in sync.
 */
public final class ModelWrapper extends BakedModelWrapper<BakedModel> {

    private static final Set<RenderType> SUPPRESSIBLE_LAYERS = Set.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout());
    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera");
    private static boolean loggedOnce = false;

    public ModelWrapper(BakedModel original) {
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
        int suppressedCount = 0;

        for (BakedQuad quad : quads) {
            if (tessera$routingFor(quad) == null) {
                filtered.add(quad);
            } else {
                suppressedCount++;
            }
        }

        if (!loggedOnce && state != null && suppressedCount > 0) {
            LOGGER.info("[Tessera-Debug] First block quad suppression confirmed on {}. (Further logs muted)", state.getBlock());
            loggedOnce = true;
        }

        return filtered;
    }

    @SuppressWarnings("resource")
    private AtlasSplitTarget tessera$routingFor(BakedQuad quad) {
        ResourceLocation spriteName = quad.getSprite().contents().name();
        return TesseraClient.SPLIT_ATLAS_MANAGER.routingFor(spriteName);
    }
}
