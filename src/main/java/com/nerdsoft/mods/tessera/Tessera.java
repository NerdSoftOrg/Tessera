package com.nerdsoft.mods.tessera;

import com.nerdsoft.mods.tessera.gui.TesseraDebugOverlay;
import com.nerdsoft.mods.tessera.compress.Bc7GpuSupport;
import com.nerdsoft.mods.tessera.config.TesseraConfig;
import com.nerdsoft.mods.tessera.config.TesseraRulesManager;
import com.nerdsoft.mods.tessera.datagen.DataGenerators;
import com.nerdsoft.mods.tessera.jni.NativeLibraryLoader;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = Tessera.MOD_ID, dist = Dist.CLIENT)
public final class Tessera {

    public static final String MOD_ID = "tessera";
    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera");

    public Tessera(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, TesseraConfig.SPEC);

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
            silenceGlDebugWarnings();

            boolean bc7Supported = Bc7GpuSupport.isSupported();
            if (NativeLibraryLoader.isAvailable() && !bc7Supported) {
                LOGGER.warn("Tessera native bridge loaded, but this GPU/driver does not expose "
                        + "GL_COMPRESSED_RGBA_BPTC_UNORM_ARB; falling back to vanilla atlas behavior.");
            }
        });
    }

    private void onAtlasStitched(TextureAtlasStitchedEvent event) {
        if (event.getAtlas().location().getPath().contains("blocks")) {
            TesseraDebugOverlay.bytesSavedByBC7 = 0;
        }
        LOGGER.info("Atlas {} stitched con {} sprites.",
                event.getAtlas().location(), event.getAtlas().getTextures().size());
    }

    private void onRegisterReloadListeners(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new TesseraRulesManager());
    }

    public static void silenceGlDebugWarnings() {
        try {
            if (GL.getCapabilities().OpenGL43) {
                GL43.glDebugMessageControl(
                        GL43.GL_DEBUG_SOURCE_API,
                        GL43.GL_DEBUG_TYPE_ERROR,
                        GL43.GL_DONT_CARE,
                        1280, // GL_INVALID_ENUM
                        false
                );
            }
        } catch (Throwable ignored) {
        }
    }
}