package com.nerdsoft.mods.tessera.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class TesseraRulesManager extends SimpleJsonResourceReloadListener {

    public static final Set<String> BLACKLISTED_ATLASES = new HashSet<>();
    private static final Gson GSON = new Gson();
    public static Boolean forcedDisableAnimations = null;
    public static Integer forcedVramBudgetMb = null;
    public static Integer forcedMaxStepDownAttempts = null;

    public TesseraRulesManager() {
        super(GSON, "tessera/rules");
    }

    public static boolean shouldDisableAnimations() {
        return Objects.requireNonNullElseGet(forcedDisableAnimations, TesseraConfig.DISABLE_ANIMATIONS);
    }

    @SuppressWarnings("unused")
    public static int getMaxQualityStepDownAttempts() {
        return Objects.requireNonNullElseGet(forcedMaxStepDownAttempts, TesseraConfig.MAX_QUALITY_STEP_DOWN_ATTEMPTS);
    }

    @Override
    public @NotNull String getName() {
        return "Tessera Resource Rules Reload Listener";
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profilerFiller) {
        BLACKLISTED_ATLASES.clear();
        forcedDisableAnimations = null;
        forcedVramBudgetMb = null;
        forcedMaxStepDownAttempts = null;

        for (JsonElement element : resources.values()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject json = element.getAsJsonObject();

            if (json.has("force_disable_animations")) {
                forcedDisableAnimations = json.get("force_disable_animations").getAsBoolean();
            }
            if (json.has("vram_budget_target_mb")) {
                forcedVramBudgetMb = json.get("vram_budget_target_mb").getAsInt();
            }
            if (json.has("max_quality_step_down_attempts")) {
                forcedMaxStepDownAttempts = json.get("max_quality_step_down_attempts").getAsInt();
            }
            if (json.has("blacklisted_atlases")) {
                json.getAsJsonArray("blacklisted_atlases").forEach(atlasElement ->
                        BLACKLISTED_ATLASES.add(atlasElement.getAsString())
                );
            }
        }
    }
}