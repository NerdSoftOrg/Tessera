package com.nerdsoft.mods.tessera.vram;

import com.nerdsoft.mods.tessera.config.Config;
import com.nerdsoft.mods.tessera.config.RulesManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VramBudgetEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/VramBudgetEngine");

    private static final int GL_NVX_GPU_MEMORY_INFO_DEDICATED_VIDMEM = 0x9047;
    private static final int GL_ATI_MEMINFO_VBO_FREE_MEMORY = 0x87FC;

    private static volatile Integer cachedHardwareVramMb;

    private VramBudgetEngine() {
    }

    public static int getEffectiveBudgetMb() {
        if (RulesManager.forcedVramBudgetMb != null) {
            return RulesManager.forcedVramBudgetMb;
        }

        int hardwareVram = queryHardwareVramMb();
        if (hardwareVram > 0) {
            return (int) (hardwareVram * 0.75);
        }

        return Config.VRAM_BUDGET_TARGET_MB.get();
    }

    private static int queryHardwareVramMb() {
        Integer cached = cachedHardwareVramMb;
        if (cached != null) {
            return cached;
        }
        return detectAndCacheHardwareVramMb();
    }

    private static synchronized int detectAndCacheHardwareVramMb() {
        if (cachedHardwareVramMb != null) {
            return cachedHardwareVramMb;
        }

        int result = queryHardwareVramMbUncached();
        cachedHardwareVramMb = result;
        return result;
    }

    private static int queryHardwareVramMbUncached() {
        GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (Throwable t) {
            LOGGER.debug("Failed to read OpenGL capabilities for VRAM query.", t);
            return -1;
        }

        if (caps.GL_NVX_gpu_memory_info) {
            try {
                int[] query = new int[4];
                GL11.glGetIntegerv(GL_NVX_GPU_MEMORY_INFO_DEDICATED_VIDMEM, query);
                if (query[0] > 0) {
                    return query[0] / 1024;
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to query VRAM via GL_NVX_gpu_memory_info.", e);
            }
        }

        if (caps.GL_ATI_meminfo) {
            try {
                int[] query = new int[4];
                GL11.glGetIntegerv(GL_ATI_MEMINFO_VBO_FREE_MEMORY, query);
                if (query[0] > 0) {
                    return query[0] / 1024;
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to query VRAM via GL_ATI_meminfo.", e);
            }
        }

        return -1;
    }

    public static boolean isWithinBudget(long estimatedBytes, int currentQualityStep) {
        double estimatedMb = estimatedBytes / (1024.0 * 1024.0);
        int budgetMb = getEffectiveBudgetMb();

        if (estimatedMb <= budgetMb) {
            return true;
        }

        LOGGER.warn("Atlas ({} MB) exceeds VRAM target ({} MB). Reduction attempt: {}",
                String.format("%.2f", estimatedMb), budgetMb, currentQualityStep);
        return false;
    }
}
