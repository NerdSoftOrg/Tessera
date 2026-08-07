package com.nerdsoft.mods.tessera.gui;

import com.nerdsoft.mods.tessera.Tessera;
import com.nerdsoft.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Locale;

@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT)
public final class TesseraDebugOverlay {

    private static final int GL_GPU_MEM_INFO_TOTAL_AVAILABLE_NVX = 0x9048;
    private static final int GL_GPU_MEM_INFO_CURRENT_AVAILABLE_NVX = 0x9049;
    private static final int GL_VBO_FREE_MEMORY_ATI = 0x87FC;

    public static boolean isCompressedAtlasActive = false;
    public static long bytesSavedByBC7 = 0;
    public static long totalCompressedAtlasBytes = 0;

    private TesseraDebugOverlay() {
    }

    @SubscribeEvent
    public static void onRenderDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.gui.getDebugOverlay().showDebugScreen()) {
            List<String> rightList = event.getRight();

            rightList.add("");
            rightList.add(" §d[Tessera Engine]");

            int budgetTargetMB = VramBudgetEngine.getEffectiveBudgetMb();
            boolean isMac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

            if (isMac) {
                rightList.add(" BC7 Compression: §cUNSUPPORTED (macOS)§r");
                return;
            }

            if (!isCompressedAtlasActive || bytesSavedByBC7 <= 0) {
                rightList.add(" BC7 Compression: §cDISABLED§r");
                rightList.add(String.format(" VRAM Target Budget: %d MB", budgetTargetMB));
                rightList.add(" GPU VRAM: " + getHardwareVramUsage());
                return;
            }

            rightList.add(" BC7 Compression: §aENABLED§r");

            double savedMB = bytesSavedByBC7 / (1024.0 * 1024.0);
            double compressedMB = totalCompressedAtlasBytes / (1024.0 * 1024.0);
            double originalMB = compressedMB + savedMB;
            double percentageSaved = originalMB > 0 ? (savedMB / originalMB) * 100.0 : 0;

            rightList.add(String.format(" Atlas VRAM: §b%.2f MB§r / §7%.2f MB§r (§a-%.1f%%§r)",
                    compressedMB, originalMB, percentageSaved));
            rightList.add(String.format(" Saved: §a%.2f MB§r", savedMB));
            rightList.add(String.format(" VRAM Target Budget: %d MB", budgetTargetMB));

            rightList.add(" GPU VRAM: " + getHardwareVramUsage());
        }
    }

    private static String getHardwareVramUsage() {
        try {
            var caps = GL.getCapabilities();

            // NVIDIA extension
            if (caps.GL_NVX_gpu_memory_info) {
                int totalKb = GL11.glGetInteger(GL_GPU_MEM_INFO_TOTAL_AVAILABLE_NVX);
                int freeKb = GL11.glGetInteger(GL_GPU_MEM_INFO_CURRENT_AVAILABLE_NVX);
                int usedKb = totalKb - freeKb;
                return String.format("%dMB / %dMB", usedKb / 1024, totalKb / 1024);
            }

            // AMD extension
            if (caps.GL_ATI_meminfo) {
                int[] info = new int[4];
                GL11.glGetIntegerv(GL_VBO_FREE_MEMORY_ATI, info);
                return String.format("Free: %dMB", info[0] / 1024);
            }
        } catch (Throwable ignored) {
            // Driver extension query failed
        }

        // Fallback for Intel GPUs or drivers without legacy GL extensions
        return "§7N/A (Driver Limited)§r";
    }
}