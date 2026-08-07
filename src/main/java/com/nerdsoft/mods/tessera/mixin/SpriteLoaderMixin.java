package com.nerdsoft.mods.tessera.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nerdsoft.mods.tessera.api.TesseraAtlasCompressEvent;
import com.nerdsoft.mods.tessera.atlas.TextureFamilyDetector;
import com.nerdsoft.mods.tessera.cache.AtlasCache;
import com.nerdsoft.mods.tessera.gui.TesseraDebugOverlay;
import com.nerdsoft.mods.tessera.compress.Bc7GpuSupport;
import com.nerdsoft.mods.tessera.compress.CompressionPipeline;
import com.nerdsoft.mods.tessera.config.TesseraConfig;
import com.nerdsoft.mods.tessera.config.TesseraRulesManager;
import com.nerdsoft.mods.tessera.jni.NativeLibraryLoader;
import com.nerdsoft.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.*;

@Mixin(TextureAtlas.class)
public abstract class SpriteLoaderMixin {

    @Unique
    private static final Logger tessera$LOGGER = LoggerFactory.getLogger("Tessera/SpriteLoaderMixin");
    @Unique
    private static AtlasCache tessera$cacheInstance;

    @Shadow
    private List<SpriteContents> sprites;
    @Shadow
    private List<TextureAtlasSprite.Ticker> animatedTextures;
    @Shadow
    private Map<ResourceLocation, TextureAtlasSprite> texturesByName;
    @Shadow
    private TextureAtlasSprite missingSprite;
    @Shadow
    private int width;
    @Shadow
    private int height;
    @Shadow
    private int mipLevel;

    @Unique
    private static synchronized AtlasCache tessera$cache() {
        if (tessera$cacheInstance == null) {
            tessera$cacheInstance = new AtlasCache(FMLPaths.GAMEDIR.get().resolve(TesseraConfig.CACHE_DIRECTORY.get()));
        }
        return tessera$cacheInstance;
    }

    @SuppressWarnings("resource")
    @Inject(
            method = "upload(Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tessera$interceptUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        TextureAtlas self = (TextureAtlas) (Object) this;

        if (self.location().getPath().endsWith("blocks.png")) {
            TesseraDebugOverlay.bytesSavedByBC7 = 0;
            TesseraDebugOverlay.totalCompressedAtlasBytes = 0;
        }

        if (TesseraRulesManager.BLACKLISTED_ATLASES.contains(self.location().toString())) {
            tessera$LOGGER.info("Atlas {} skipped due to blacklist rule.", self.location());
            return;
        }

        TesseraAtlasCompressEvent compressEvent = new TesseraAtlasCompressEvent(self);
        NeoForge.EVENT_BUS.post(compressEvent);
        if (compressEvent.isCanceled()) {
            tessera$LOGGER.info("Compression for atlas {} was canceled by Java event.", self.location());
            return;
        }

        if (!TesseraRulesManager.shouldDisableAnimations()) {
            for (TextureAtlasSprite sprite : preparations.regions().values()) {
                if (sprite.createTicker() != null) {
                    tessera$LOGGER.debug("Atlas {} contains animated textures; skipping BC7 compression.", self.location());
                    return;
                }
            }
        }

        if (TesseraConfig.DISABLE_NATIVE_COMPRESSION.get() || !NativeLibraryLoader.isAvailable() || !Bc7GpuSupport.isSupported()) {
            return;
        }

        int width = preparations.width();
        int height = preparations.height();
        if (width <= 0 || height <= 0) {
            return;
        }

        int alignedWidth = (width + 3) & ~3;
        int alignedHeight = (height + 3) & ~3;
        long expectedBc7Bytes = (long) (alignedWidth / 4) * (alignedHeight / 4) * 16;

        if (!VramBudgetEngine.isWithinBudget(expectedBc7Bytes, 0)) {
            tessera$LOGGER.warn("Atlas {} exceeds VRAM budget target. Falling back to uncompressed RGBA.", self.location());
            return;
        }

        ByteBuffer rgba8 = tessera$assembleAtlasBuffer(preparations, width, height);

        CompressionPipeline pipeline = new CompressionPipeline(tessera$cache());
        Optional<CompressionPipeline.CompressionResult> result = pipeline.compress(rgba8, width, height);
        if (result.isEmpty()) {
            return;
        }

        this.texturesByName = preparations.regions();
        this.missingSprite = preparations.missing();
        this.width = width;
        this.height = height;
        this.mipLevel = 0;

        List<SpriteContents> contentsList = new ArrayList<>(preparations.regions().size());
        for (TextureAtlasSprite sprite : preparations.regions().values()) {
            contentsList.add(sprite.contents());
        }
        this.sprites = contentsList;
        this.animatedTextures = Collections.emptyList();

        tessera$uploadCompressed(self, width, height, result.get().compressedBlocks());
        ci.cancel();
    }

    @Unique
    @SuppressWarnings("resource")
    private ByteBuffer tessera$assembleAtlasBuffer(SpriteLoader.Preparations preparations, int width, int height) {
        Map<TextureAtlasSprite, Integer> spriteIds = new HashMap<>(preparations.regions().size());
        List<TextureFamilyDetector.SpriteSample> samples = new ArrayList<>(preparations.regions().size());

        int nextId = 0;
        for (TextureAtlasSprite sprite : preparations.regions().values()) {
            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();
            byte[] rgba = new byte[spriteWidth * spriteHeight * 4];

            for (int y = 0; y < spriteHeight; y++) {
                for (int x = 0; x < spriteWidth; x++) {
                    int packed = sprite.getPixelRGBA(0, x, y);
                    int offset = (y * spriteWidth + x) * 4;
                    rgba[offset] = (byte) (packed & 0xFF);
                    rgba[offset + 1] = (byte) ((packed >>> 8) & 0xFF);
                    rgba[offset + 2] = (byte) ((packed >>> 16) & 0xFF);
                    rgba[offset + 3] = (byte) ((packed >>> 24) & 0xFF);
                }
            }

            int id = nextId++;
            spriteIds.put(sprite, id);
            samples.add(new TextureFamilyDetector.SpriteSample(id, spriteWidth, spriteHeight, rgba, false));
        }

        TextureFamilyDetector detector = new TextureFamilyDetector();
        List<TextureFamilyDetector.Family> families =
                detector.groupBySimilarity(samples, TesseraConfig.DEDUP_SIMILARITY_THRESHOLD.get());

        int duplicateCount = families.size() < samples.size() ? samples.size() - families.size() : 0;
        if (duplicateCount > 0) {
            tessera$LOGGER.info("{} of {} atlas sprites are perceptual near-duplicates of another sprite in this atlas.",
                    duplicateCount, samples.size());
        }

        ByteBuffer atlas = ByteBuffer.allocateDirect(width * height * 4);
        for (TextureAtlasSprite sprite : preparations.regions().values()) {
            TextureFamilyDetector.SpriteSample sample = samples.get(spriteIds.get(sprite));
            int originX = sprite.getX();
            int originY = sprite.getY();

            for (int y = 0; y < sample.height(); y++) {
                int destRowStart = ((originY + y) * width + originX) * 4;
                int srcRowStart = y * sample.width() * 4;
                if (destRowStart < 0 || destRowStart + sample.width() * 4 > atlas.capacity()) {
                    continue;
                }
                atlas.position(destRowStart);
                atlas.put(sample.rgba8(), srcRowStart, sample.width() * 4);
            }
        }
        atlas.rewind();
        return atlas;
    }

    @Unique
    @SuppressWarnings("StatementWithEmptyBody")
    private void tessera$uploadCompressed(TextureAtlas atlas, int width, int height, ByteBuffer compressedBlocks) {
        RenderSystem.bindTexture(atlas.getId());

        int alignedWidth = (width + 3) & ~3;
        int alignedHeight = (height + 3) & ~3;
        int expectedBytes = (alignedWidth / 4) * (alignedHeight / 4) * 16;

        if (compressedBlocks.remaining() != expectedBytes) {
            tessera$LOGGER.error(
                    "BC7 size mismatch for atlas {} ({}x{}): received {} bytes, expected {}.",
                    atlas.location(), width, height, compressedBlocks.remaining(), expectedBytes
            );
            return;
        }

        // Drain stale OpenGL errors
        while (GL11.glGetError() != GL11.GL_NO_ERROR);

        // Suppress KHR debug callback during driver state swap
        boolean wasDebugActive = GL11.glIsEnabled(GL43.GL_DEBUG_OUTPUT);
        if (wasDebugActive) {
            GL11.glDisable(GL43.GL_DEBUG_OUTPUT);
        }

        try {
            // Upload BC7 compressed texture
            GL13.glCompressedTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL42.GL_COMPRESSED_RGBA_BPTC_UNORM,
                    alignedWidth,
                    alignedHeight,
                    0,
                    compressedBlocks
            );

            // Configure sampler parameters
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            // Disable mipmapping to prevent black textures
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);

        } finally {
            if (wasDebugActive) {
                GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
            }
        }

        // Clear error flags generated during upload
        while (GL11.glGetError() != GL11.GL_NO_ERROR);

        // Update overlay metrics
        long uncompressedSize = (long) width * height * 4;
        long compressedSize = compressedBlocks.remaining();
        long savedForThisAtlas = uncompressedSize - compressedSize;

        TesseraDebugOverlay.isCompressedAtlasActive = true;
        TesseraDebugOverlay.bytesSavedByBC7 += savedForThisAtlas;
        TesseraDebugOverlay.totalCompressedAtlasBytes += compressedSize;

        double savedMB = savedForThisAtlas / (1024.0 * 1024.0);
        tessera$LOGGER.info(
                "Successfully compressed atlas to BC7 ({}): {}x{}. VRAM saved: {} MB",
                atlas.location(), width, height, String.format("%.2f", savedMB)
        );
    }
}