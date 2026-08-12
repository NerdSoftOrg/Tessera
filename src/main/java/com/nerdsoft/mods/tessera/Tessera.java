package com.nerdsoft.mods.tessera;

import com.nerdsoft.mods.tessera.atlas.AtlasCompressionDriver;
import com.nerdsoft.mods.tessera.compress.Bc1ComputeSupport;
import com.nerdsoft.mods.tessera.compress.Bc1TextureFormatSupport;
import com.nerdsoft.mods.tessera.compress.Bc7GpuSupport;
import com.nerdsoft.mods.tessera.config.Config;
import com.nerdsoft.mods.tessera.config.RulesManager;
import com.nerdsoft.mods.tessera.datagen.DataGenerators;
import com.nerdsoft.mods.tessera.gui.KnownEngineBugLogFilter;
import com.nerdsoft.mods.tessera.jni.NativeLibraryLoader;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
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

    /**
     * Snapshot of the reload-sensitive settings as of the last time they
     * were known-applied (mod construction, or the last
     * {@link #onConfigReloading} that acted on a change). Compared against
     * the just-reloaded values in {@link #onConfigReloading} to decide
     * whether a resource reload is actually warranted -- see
     * {@link Config.ReloadSensitiveSnapshot} for which settings this
     * covers and why only those two.
     */
    private Config.ReloadSensitiveSnapshot lastAppliedSettings;

    public Tessera(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                ConfigurationScreen::new
        );

        // Installed here (mod construction) rather than in onClientSetup's
        // enqueueWork below: unlike the GL-capability warmup calls, this
        // only configures Log4j2 logging infrastructure and touches no GL
        // state, so it needs no render-thread/GL-context timing and should
        // run as early as possible -- before the very first resource
        // reload (and therefore the very first atlas stitch) can happen,
        // since that is exactly when MC-293754's spam starts. See
        // KnownEngineBugLogFilter's own class doc for what this suppresses
        // and why it's scoped as narrowly as it is.
        KnownEngineBugLogFilter.install();

        NativeLibraryLoader.initialize();

        modEventBus.addListener(this::onConfigLoading);

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onAtlasStitched);
        modEventBus.addListener(this::onRegisterReloadListeners);
        modEventBus.addListener(this::onConfigReloading);

        DataGenerators.register(modEventBus);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Render-thread warmup for all three GL-capability caches.
            // GL.getCapabilities() is thread-local to whatever thread has
            // a context current -- SplitAtlasManager's background stitch
            // executor is the first real caller of the BC1 checks at
            // runtime, has no context, and previously poisoned both
            // caches to `false` forever the moment a reload ran (see
            // Bc1TextureFormatSupport/Bc1ComputeSupport class docs for the
            // full root-cause writeup). Bc7GpuSupport was already warmed
            // here; BC1's two checks were the ones missing this call,
            // which is why only the opaque (BC1) atlas ever reported
            // "SKIPPED (compression unavailable)" while alpha (BC7)
            // compressed correctly.
            boolean bc7Supported = Bc7GpuSupport.isSupported();
            Bc1TextureFormatSupport.warmUp();
            Bc1ComputeSupport.warmUp();

            if (NativeLibraryLoader.isAvailable() && !bc7Supported) {
                LOGGER.warn("Tessera native bridge loaded, but this GPU/driver does not expose "
                        + "GL_COMPRESSED_RGBA_BPTC_UNORM_ARB; falling back to vanilla atlas behavior.");
            }
            if (NativeLibraryLoader.isAvailable() && !Bc1TextureFormatSupport.isSupported()) {
                LOGGER.warn("Tessera native bridge loaded, but this GPU/driver does not expose "
                        + "GL_EXT_texture_compression_s3tc; opaque atlas will remain uncompressed RGBA8.");
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

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            this.lastAppliedSettings = Config.ReloadSensitiveSnapshot.capture();
        }
    }

    /**
     * Fires whenever {@link Config#SPEC}'s backing file changes on disk,
     * including the {@code ConfigurationScreen}'s save-on-close path.
     * Triggers a client resource reload only when one of
     * {@link Config.ReloadSensitiveSnapshot}'s two settings actually
     * changed value -- {@link Config#CACHE_DIRECTORY} (so
     * {@link AtlasCompressionDriver#cache()} rebuilds against the new
     * directory instead of staying pinned to whichever one was first read
     * this session) and {@link Config#DISABLE_ANIMATIONS} (so
     * {@code SpriteRoutingMixin} re-evaluates which sprites are eligible
     * for the static split). Every other setting in this spec is either
     * read fresh next time it's needed with no reload required
     * ({@code compressionQuality}, {@code vramBudgetTargetMb}, dedup
     * settings) or purely cosmetic ({@code showExtendedDebugBreakdown}) --
     * forcing a reload for those would stall a frame on every settings
     * screen close for no behavioral change.
     */
    @SuppressWarnings("JavadocReference")
    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != Config.SPEC) {
            return;
        }

        Config.ReloadSensitiveSnapshot current = Config.ReloadSensitiveSnapshot.capture();
        Config.ReloadSensitiveSnapshot previous = this.lastAppliedSettings;
        this.lastAppliedSettings = current;

        if (current.equals(previous)) {
            return;
        }

        if (!current.cacheDirectory().equals(previous.cacheDirectory())) {
            AtlasCompressionDriver.invalidateCache();
        }

        LOGGER.info("Tessera settings changed in a way that affects atlas splitting ({} -> {}); "
                + "reloading resources to apply.", previous, current);

        // ModConfigEvent.Reloading can fire from NightConfig's own
        // background file-watcher thread, not just the render thread (the
        // ConfigurationScreen save path happens to already be on the
        // render thread, but this listener has no way to distinguish that
        // from a direct file edit picked up by the watcher). Minecraft's
        // resource-reload entry point asserts render-thread ownership, so
        // this must always hop through Minecraft#execute rather than
        // calling reloadResourcePacks() directly here.
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().reloadResourcePacks());
    }
}