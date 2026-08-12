package com.nerdsoft.mods.tessera.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue COMPRESSION_QUALITY;
    public static final ModConfigSpec.IntValue DEDUP_SIMILARITY_THRESHOLD;
    public static final ModConfigSpec.BooleanValue DEDUP_SKIP_DUPLICATE_ENCODING;
    public static final ModConfigSpec.IntValue VRAM_BUDGET_TARGET_MB;
    public static final ModConfigSpec.IntValue MAX_QUALITY_STEP_DOWN_ATTEMPTS;
    public static final ModConfigSpec.ConfigValue<String> CACHE_DIRECTORY;
    public static final ModConfigSpec.BooleanValue DISABLE_NATIVE_COMPRESSION;
    public static final ModConfigSpec.BooleanValue DISABLE_ANIMATIONS;
    public static final ModConfigSpec.BooleanValue SHOW_EXTENDED_DEBUG_BREAKDOWN;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("compression");

        COMPRESSION_QUALITY = builder
                .comment(
                        "BC7 compression quality preset, 0 (fastest, lowest fidelity) to 7 (slowest, highest fidelity).",
                        "Maps to a fixed rdo_bc_params preset table in the native bridge, not a linear passthrough value."
                )
                .translation("tessera.configuration.compressionQuality")
                .defineInRange("compressionQuality", 4, 0, 7);

        DISABLE_NATIVE_COMPRESSION = builder
                .comment("Forces vanilla atlas behavior even when the native compression library loaded successfully.")
                .translation("tessera.configuration.disableNativeCompression")
                .define("disableNativeCompression", false);

        DISABLE_ANIMATIONS = builder
                .comment(
                        "Forces freezing of texture animations to allow BC7 compression on massive atlases (blocks.png, gui.png).",
                        "Useful for maximum VRAM savings in large modpacks like ATM10 at the cost of static water/lava/GUI animations."
                )
                .translation("tessera.configuration.disableAnimationsForMaxVramSavings")
                .define("disableAnimationsForMaxVramSavings", false);

        builder.pop();

        builder.push("deduplication");

        DEDUP_SIMILARITY_THRESHOLD = builder
                .comment(
                        "Maximum Hamming distance, 0 to 64, between two 64-bit pHash fingerprints for two sprites",
                        "to be treated as duplicates. Lower is stricter."
                )
                .translation("tessera.configuration.dedupSimilarityThreshold")
                .defineInRange("dedupSimilarityThreshold", 6, 0, 64);

        DEDUP_SKIP_DUPLICATE_ENCODING = builder
                .comment(
                        "EXPERIMENTAL — Section 4/14 step 9. When enabled, near-duplicate sprites (per the",
                        "threshold above) are excluded from the Stitcher's bin-packing entirely and aliased",
                        "to their representative's atlas region instead, actually reducing resident VRAM",
                        "(not just re-encode CPU cost). Defaults to false (opt-in) since this changes which",
                        "sprites occupy their own atlas region, which is a more invasive change than any",
                        "other config in this mod — worth testing on a specific modpack before relying on it."
                )
                .translation("tessera.configuration.dedupSkipDuplicateEncoding")
                .define("dedupSkipDuplicateEncoding", false);

        builder.pop();

        builder.push("vramBudget");

        VRAM_BUDGET_TARGET_MB = builder
                .comment(
                        "Advisory VRAM target in megabytes for the compressed atlas. Evaluated once per",
                        "resource-pack or mod-list reload, not polled continuously at runtime."
                )
                .translation("tessera.configuration.vramBudgetTargetMb")
                .defineInRange("vramBudgetTargetMb", 2048, 256, 16384);

        MAX_QUALITY_STEP_DOWN_ATTEMPTS = builder
                .comment("Maximum number of quality-preset step-downs attempted to fit the VRAM budget before giving up.")
                .translation("tessera.configuration.maxQualityStepDownAttempts")
                .defineInRange("maxQualityStepDownAttempts", 3, 0, 7);

        builder.pop();

        builder.push("cache");

        CACHE_DIRECTORY = builder
                .comment("Cache directory for compressed atlas data, relative to the game directory.")
                .translation("tessera.configuration.cacheDirectory")
                .define("cacheDirectory", "tessera-cache");

        builder.pop();

        builder.push("debug");

        SHOW_EXTENDED_DEBUG_BREAKDOWN = builder
                .comment("Displays the expanded breakdown by atlas and by bucket on the F3 Debug Screen.")
                .translation("tessera.configuration.showExtendedDebugBreakdown")
                .define("showExtendedDebugBreakdown", false);

        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}