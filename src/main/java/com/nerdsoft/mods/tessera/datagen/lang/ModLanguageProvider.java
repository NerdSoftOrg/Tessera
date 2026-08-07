package com.nerdsoft.mods.tessera.datagen.lang;

import com.nerdsoft.mods.tessera.Tessera;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public abstract class ModLanguageProvider extends LanguageProvider {

    public ModLanguageProvider(PackOutput output, String locale) {
        super(output, Tessera.MOD_ID, locale);
    }

    protected void addConfigCategory(String key, String title, String tooltip, String buttonText) {
        add("tessera.configuration." + key, title);
        add("tessera.configuration." + key + ".tooltip", tooltip);
        add("tessera.configuration." + key + ".button", buttonText);
    }

    protected void addConfigOption(String key, String name, String tooltip) {
        add("tessera.configuration." + key, name);
        add("tessera.configuration." + key + ".tooltip", tooltip);
    }
}