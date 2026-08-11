package com.nerdsoft.mods.tessera.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {

    @Accessor("handledDebugKey")
    void setHandledDebugKey(boolean handled);
}