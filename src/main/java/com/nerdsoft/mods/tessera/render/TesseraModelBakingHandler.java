package com.nerdsoft.mods.tessera.render;

import com.nerdsoft.mods.tessera.Tessera;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Applies {@link TesseraModelWrapper} to every block model, via the
 * confirmed {@code ModelEvent.ModifyBakingResult} event and
 * {@code event.getBakingResult().blockStateModels().computeIfPresent(...)}
 * pattern documented by NeoForged.
 *
 * <h2>Why every block, not a filtered subset</h2>
 * At bake time (when this event fires) Tessera's split-atlas routing table
 * ({@code TesseraSplitAtlasManager.routingFor}) is not guaranteed to be
 * populated yet for the <em>current</em> reload -- model baking and atlas
 * stitching are both async phases of the same resource reload, and their
 * relative ordering is not confirmed. Rather than risk wrapping too few
 * models based on stale/absent routing data, every block model is wrapped;
 * {@link TesseraModelWrapper} itself checks the routing table lazily, per
 * call, at actual render/compile time (by which point the reload has fully
 * completed). The cost of wrapping an unaffected model is one extra
 * virtual-call indirection per {@code getQuads} call, not a correctness
 * risk.
 *
 * <p>This event fires from a worker thread per NeoForge's own
 * documentation, so nothing here may touch GL or assume render-thread
 * context -- confirmed safe as written (registry lookups only).
 */
// Compatibility for 1.21
@SuppressWarnings("removal")
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class TesseraModelBakingHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/ModelBakingHandler");

    private TesseraModelBakingHandler() {
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        int wrapped = 0;

        for (Map.Entry<ModelResourceLocation, BakedModel> entry : models.entrySet()) {
            BakedModel originalModel = entry.getValue();

            // Prevent double-wrapping if another handler or reload triggered
            if (!(originalModel instanceof TesseraModelWrapper)) {
                entry.setValue(new TesseraModelWrapper(originalModel));
                wrapped++;
            }
        }

        LOGGER.info("Wrapped {} block state models with Tessera's split-atlas quad suppression.", wrapped);
    }
}