package com.nerdsoft.mods.tessera.datagen;

import com.nerdsoft.mods.tessera.datagen.lang.EnUsLanguageProvider;
import com.nerdsoft.mods.tessera.datagen.lang.EsEsLanguageProvider;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.data.event.GatherDataEvent;

public final class DataGenerators {

    private DataGenerators() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(DataGenerators::gatherData);
    }

    private static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();

        generator.addProvider(event.includeClient(), new EnUsLanguageProvider(packOutput));
        generator.addProvider(event.includeClient(), new EsEsLanguageProvider(packOutput));
    }
}