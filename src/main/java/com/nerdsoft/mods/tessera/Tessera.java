package com.nerdsoft.mods.tessera;

import com.nerdsoft.mods.tessera.compress.Bc7GpuSupport;
import com.nerdsoft.mods.tessera.config.Config;
import com.nerdsoft.mods.tessera.config.RulesManager;
import com.nerdsoft.mods.tessera.datagen.DataGenerators;
import com.nerdsoft.mods.tessera.jni.NativeLibraryLoader;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = Tessera.MOD_ID, dist = Dist.CLIENT)
public final class Tessera {

    public static final String MOD_ID = "tessera";
    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera");

    public Tessera(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                ConfigurationScreen::new
        );

        NativeLibraryLoader.initialize();

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onAtlasStitched);
        modEventBus.addListener(this::onRegisterReloadListeners);

        DataGenerators.register(modEventBus);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            boolean bc7Supported = Bc7GpuSupport.isSupported();
            if (NativeLibraryLoader.isAvailable() && !bc7Supported) {
                LOGGER.warn("Tessera native bridge loaded, but this GPU/driver does not expose "
                        + "GL_COMPRESSED_RGBA_BPTC_UNORM_ARB; falling back to vanilla atlas behavior.");
            }
        });
    }

    private void onAtlasStitched(TextureAtlasStitchedEvent event) {
        // Per-atlas counter resets are now handled generically in
        // SpriteLoaderMixin#tessera$interceptUpload via TesseraDebugOverlay.resetAtlas(),
        // which subtracts only this atlas's own prior contribution instead of zeroing
        // the whole aggregate (which would also erase other atlases' recorded savings).
        LOGGER.info("Atlas {} stitched with {} sprites.",
                event.getAtlas().location(), event.getAtlas().getTextures().size());
    }

    private void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new RulesManager());
        // Registered purely for reload-lifecycle participation -- see
        // TesseraSplitAtlasManager's class doc for why its actual
        // stitch/upload work rides on SpriteLoader.stitch()/TextureAtlas
        // .upload() via mixins rather than this listener's own reload().
        event.registerReloadListener(TesseraClient.SPLIT_ATLAS_MANAGER);
    }
}