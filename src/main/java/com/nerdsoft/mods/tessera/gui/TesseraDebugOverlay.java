package com.nerdsoft.mods.tessera.gui;

import com.nerdsoft.mods.tessera.Tessera;
import com.nerdsoft.mods.tessera.config.TesseraConfig;
import com.nerdsoft.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Compatibility for 1.21
@SuppressWarnings("removal")
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class TesseraDebugOverlay {

    private static final int GL_GPU_MEM_INFO_TOTAL_AVAILABLE_NVX = 0x9048;
    private static final int GL_GPU_MEM_INFO_CURRENT_AVAILABLE_NVX = 0x9049;
    private static final int GL_VBO_FREE_MEMORY_ATI = 0x87FC;


    private static final long HARDWARE_VRAM_REFRESH_INTERVAL_MS = 1000L;

    private static volatile String cachedHardwareVramUsage = "§7N/A (Driver Limited)§r";
    private static volatile long lastHardwareVramQueryMs = -1L;

    public static boolean isCompressedAtlasActive = false;
    public static long bytesSavedByBC7 = 0;
    public static long totalCompressedAtlasBytes = 0;


    private static final Map<String, AtlasStats> perAtlasStats = new ConcurrentHashMap<>();

    public record AtlasStats(long bytesSaved, long compressedBytes) {
    }

    /**
     * Per-bucket breakdown within a single atlas -- e.g. "blocks.png/OPAQUE_BC1"
     * vs "blocks.png/ALPHA_BC7"-- so the debug overlay can show which of
     * the three physical textures is contributing how much to an atlas's
     * total savings, not just the atlas-wide sum. Keyed by
     * {@code atlasLocation + "/"+ bucketName} rather than a nested map, to
     * keep the accumulation semantics (additive merge, same as
     * {@link #perAtlasStats}) identical between the two maps.
     */
    private static final Map<String, AtlasStats> perBucketStats = new ConcurrentHashMap<>();


    @SuppressWarnings("unused")
    public static void recordCompression(String atlasLocation, long savedBytes, long compressedBytes) {
        perAtlasStats.merge(atlasLocation, new AtlasStats(savedBytes, compressedBytes),
                (existing, added) -> new AtlasStats(
                        existing.bytesSaved() + added.bytesSaved(),
                        existing.compressedBytes() + added.compressedBytes()));

        isCompressedAtlasActive = true;
        bytesSavedByBC7 += savedBytes;
        totalCompressedAtlasBytes += compressedBytes;
    }

    /**
     * Same accumulation as {@link #recordCompression}, scoped to a single
     * bucket within an atlas. Called once per non-empty bucket (up to 3x
     * per atlas) in addition to the atlas-wide call, so the two maps stay
     * consistent: summing a given atlas's entries in {@link #perBucketStats}
     * always equals its single entry in {@link #perAtlasStats}.
     */
    @SuppressWarnings("unused")
    public static void recordBucketCompression(String atlasLocation, String bucketName, long savedBytes, long compressedBytes) {
        String key = atlasLocation + "/" + bucketName;
        perBucketStats.merge(key, new AtlasStats(savedBytes, compressedBytes),
                (existing, added) -> new AtlasStats(
                        existing.bytesSaved() + added.bytesSaved(),
                        existing.compressedBytes() + added.compressedBytes()));
    }


    @SuppressWarnings("unused")
    public static void resetAtlas(String atlasLocation) {
        AtlasStats removed = perAtlasStats.remove(atlasLocation);
        if (removed != null) {
            bytesSavedByBC7 -= removed.bytesSaved();
            totalCompressedAtlasBytes -= removed.compressedBytes();
        }
        String prefix = atlasLocation + "/";
        perBucketStats.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @SuppressWarnings("unused")
    public static Map<String, AtlasStats> getPerAtlasStats() {
        return Map.copyOf(perAtlasStats);
    }

    private TesseraDebugOverlay() {
    }

    @SubscribeEvent
    public static void onRenderDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.gui.getDebugOverlay().showDebugScreen()) {
            List<String> rightList = event.getRight();
            List<String> tesseraLines = new ArrayList<>();

            tesseraLines.add("");
            tesseraLines.add("§d[Tessera]");

            int budgetTargetMB = VramBudgetEngine.getEffectiveBudgetMb();
            boolean isMac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

            if (isMac) {
                tesseraLines.add("Compression: §cUNSUPPORTED (macOS)§r");
            } else if (!isCompressedAtlasActive || bytesSavedByBC7 <= 0) {
                tesseraLines.add("Compression: §cDISABLED§r");
                tesseraLines.add(String.format("VRAM Budget: %d MB", budgetTargetMB));
                tesseraLines.add("VRAM: " + getHardwareVramUsage());
            } else {
                tesseraLines.add("Compression: §aENABLED§r");

                double savedMB = bytesSavedByBC7 / (1024.0 * 1024.0);
                double compressedMB = totalCompressedAtlasBytes / (1024.0 * 1024.0);
                double originalMB = compressedMB + savedMB;
                double percentageSaved = originalMB > 0 ? (savedMB / originalMB) * 100.0 : 0;

                tesseraLines.add(String.format("Atlas VRAM: §b%.2f MB§r / §7%.2f MB§r (§a-%.1f%%§r)",
                        compressedMB, originalMB, percentageSaved));
                tesseraLines.add(String.format("Saved: §a%.2f MB§r", savedMB));
                tesseraLines.add(String.format("VRAM Budget: %d MB", budgetTargetMB));

                tesseraLines.add("GPU VRAM: " + getHardwareVramUsage());

                if (TesseraConfig.SHOW_EXTENDED_DEBUG_BREAKDOWN.get()) {
                    appendPerAtlasBreakdown(tesseraLines);
                }
            }

            int insertIndex = getIndex(rightList);

            if (insertIndex != -1 && insertIndex <= rightList.size()) {
                rightList.addAll(insertIndex, tesseraLines);
            } else {
                rightList.addAll(tesseraLines);
            }
        }
    }

    private static int getIndex(List<String> rightList) {
        for (int i = 0; i < rightList.size(); i++) {
            String line = rightList.get(i);
            // example "4.6.0 - Build 31.0.101.4502"
            if (line.contains(" - Build")) {
                return i + 1;
            }
        }
        return -1;
    }

    private static void appendPerAtlasBreakdown(List<String> rightList) {
        if (perAtlasStats.isEmpty()) {
            return;
        }

        rightList.add("§7Per-atlas:§r");
        perAtlasStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().bytesSaved(), a.getValue().bytesSaved()))
                .forEach(entry -> {
                    double atlasSavedMB = entry.getValue().bytesSaved() / (1024.0 * 1024.0);
                    rightList.add(String.format(" §7%s: §a%.2f MB§r", entry.getKey(), atlasSavedMB));
                    appendBucketBreakdownFor(rightList, entry.getKey());
                });
    }

    /**
     * Appends the per-bucket lines (OPAQUE_BC1 / ALPHA_BC7 / DYNAMIC_RGBA8)
     * nested under a single atlas's line in the breakdown, when that atlas
     * has more than one bucket recorded -- a single-bucket atlas (e.g. one
     * with no alpha sprites at all) skips this since the atlas-level line
     * already says everything the bucket line would.
     */
    private static void appendBucketBreakdownFor(List<String> rightList, String atlasLocation) {
        String prefix = atlasLocation + "/";
        List<Map.Entry<String, AtlasStats>> bucketEntries = perBucketStats.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .sorted((a, b) -> Long.compare(b.getValue().bytesSaved(), a.getValue().bytesSaved()))
                .toList();

        if (bucketEntries.size() <= 1) {
            return;
        }

        for (Map.Entry<String, AtlasStats> bucketEntry : bucketEntries) {
            String bucketName = bucketEntry.getKey().substring(prefix.length());
            double bucketSavedMB = bucketEntry.getValue().bytesSaved() / (1024.0 * 1024.0);
            double bucketResidentMB = bucketEntry.getValue().compressedBytes() / (1024.0 * 1024.0);
            rightList.add(String.format("   §8%s: §a%.2f MB§r saved, §7%.2f MB§r resident",
                    bucketName, bucketSavedMB, bucketResidentMB));
        }
    }

    private static String getHardwareVramUsage() {
        long now = System.currentTimeMillis();
        if (now - lastHardwareVramQueryMs < HARDWARE_VRAM_REFRESH_INTERVAL_MS) {
            return cachedHardwareVramUsage;
        }
        lastHardwareVramQueryMs = now;
        cachedHardwareVramUsage = queryHardwareVramUsage();
        return cachedHardwareVramUsage;
    }

    private static String queryHardwareVramUsage() {
        try {
            var caps = GL.getCapabilities();


            if (caps.GL_NVX_gpu_memory_info) {
                int totalKb = GL11.glGetInteger(GL_GPU_MEM_INFO_TOTAL_AVAILABLE_NVX);
                int freeKb = GL11.glGetInteger(GL_GPU_MEM_INFO_CURRENT_AVAILABLE_NVX);
                int usedKb = totalKb - freeKb;
                return String.format("%dMB / %dMB", usedKb / 1024, totalKb / 1024);
            }


            if (caps.GL_ATI_meminfo) {
                int[] info = new int[4];
                GL11.glGetIntegerv(GL_VBO_FREE_MEMORY_ATI, info);
                return String.format("Free: %dMB", info[0] / 1024);
            }
        } catch (Throwable ignored) {

        }


        return "§7N/A (Driver Limited)§r";
    }
}
