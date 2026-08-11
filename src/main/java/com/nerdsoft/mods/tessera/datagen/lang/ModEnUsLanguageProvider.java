package com.nerdsoft.mods.tessera.datagen.lang;

import net.minecraft.data.PackOutput;

public class ModEnUsLanguageProvider extends ModLanguageProvider {

    public ModEnUsLanguageProvider(PackOutput output) {
        super(output, "en_us");
    }

    @Override
    protected void addTranslations() {
        // Mod & Config Title
        add("modmenu.name.tessera", "Tessera");
        add("modmenu.description.tessera", "Native BC7 GPU texture compression and VRAM optimization engine.");

        // Config screen titles required by NeoForge
        add("tessera.configuration.title", "Tessera Settings");
        add("tessera.configuration.section.tessera.client.toml", "Tessera Client Config");
        add("tessera.configuration.section.tessera.client.toml.title", "Tessera Client Configuration");

        // Categories (Title, Tooltip and Submenu buttons)
        addConfigCategory("compression", "Compression", "Configure BC7 native compression behavior and animation freezing.", "Compression Settings");
        addConfigCategory("deduplication", "Deduplication", "Configure perceptual hashing and near-duplicate sprite merging.", "Deduplication Settings");
        addConfigCategory("vramBudget", "VRAM Budget", "Configure advisory VRAM limits and quality step-down logic.", "VRAM Budget Settings");
        addConfigCategory("cache", "Cache", "Configure disk cache location for compiled BC7 texture blocks.", "Cache Settings");
        addConfigCategory("debug", "Debug", "Configure debug overlay display and extended information on the F3 screen.", "Debug Settings");

        // Compression Options
        addConfigOption("compressionQuality", "Compression Quality Preset",
                "BC7 quality preset (0: Fastest to 7: Highest Fidelity). Controls compression quality vs processing time.");
        addConfigOption("disableNativeCompression", "Disable Native Compression",
                "Forces vanilla RGBA atlas behavior even if BC7 native compression is supported.");
        addConfigOption("disableAnimationsForMaxVramSavings", "Disable Texture Animations",
                "Freezes animated textures (water, lava, portal, GUIs) to allow forcing BC7 compression on massive atlases like blocks.png and gui.png.");

        // Deduplication Options
        addConfigOption("dedupSimilarityThreshold", "Deduplication Similarity Threshold",
                "Hamming distance (0-64) for 64-bit pHash fingerprints. Lower values are stricter when merging duplicate sprites.");
        addConfigOption("dedupSkipDuplicateEncoding", "Skip Duplicate Encoding",
                "Excludes near-duplicate sprites from bin-packing entirely to further reduce resident VRAM.");

        // VRAM Budget Options
        addConfigOption("vramBudgetTargetMb", "VRAM Budget Target (MB)",
                "Advisory VRAM memory limit in megabytes evaluated during atlas stitching.");
        addConfigOption("maxQualityStepDownAttempts", "Max Quality Step Down Attempts",
                "Maximum quality reduction attempts performed when trying to stay within the VRAM budget.");

        // Cache Options
        addConfigOption("cacheDirectory", "Cache Directory",
                "Folder name where compiled BC7 texture blocks are stored on disk.");

        // Debug Options
        addConfigOption("showExtendedDebugBreakdown", "Show Extended Atlas Breakdown",
                "Displays the expanded breakdown by atlas and by bucket on the F3 Debug Screen.");

        // Keybinds & Debug Messages
        add("key.categories.tessera", "Tessera");
        add("key.tessera.toggle_extended_debug", "Toggle Advanced Atlas Info");
        add("tessera.debug.advanced_atlas_info", "Advanced atlas info: %s");

        // Debug Overlay
        add("tessera.overlay.vram_saved", "VRAM Saved: %s MB (BC7 Active)");
        // LowerCases for match vanilla
        add("debug.state.hidden", "hidden");
        add("debug.state.shown", "shown");
    }
}