package com.nerdsoft.mods.tessera.keybinds;

import com.mojang.blaze3d.platform.InputConstants;
import com.nerdsoft.mods.tessera.Tessera;
import com.nerdsoft.mods.tessera.config.Config;
import com.nerdsoft.mods.tessera.mixin.KeyboardHandlerAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

// Compatibility for 1.21
@SuppressWarnings("removal")
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class TesseraKeyBinds {

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        long windowHandle = mc.getWindow().getWindow();

        if (event.getKey() == GLFW.GLFW_KEY_4 && InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_F3)) {

            ((KeyboardHandlerAccessor) mc.keyboardHandler).setHandledDebugKey(true);

            boolean newState = !Config.SHOW_EXTENDED_DEBUG_BREAKDOWN.get();
            Config.SHOW_EXTENDED_DEBUG_BREAKDOWN.set(newState);
            Config.SHOW_EXTENDED_DEBUG_BREAKDOWN.save();

            Component statusComponent = newState
                    ? Component.translatable("debug.state.shown")
                    : Component.translatable("debug.state.hidden");

            Component debugMessage = Component.empty()
                    .append(Component.literal("[Tessera Debug]: ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.translatable("tessera.debug.advanced_atlas_info", statusComponent).withStyle(ChatFormatting.WHITE));

            mc.gui.getChat().addMessage(debugMessage);
        }
    }
}