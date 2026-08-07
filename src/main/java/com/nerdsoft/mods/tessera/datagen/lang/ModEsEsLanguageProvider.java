package com.nerdsoft.mods.tessera.datagen.lang;

import net.minecraft.data.PackOutput;

public class ModEsEsLanguageProvider extends ModLanguageProvider {

    public ModEsEsLanguageProvider(PackOutput output) {
        super(output, "es_es");
    }

    @Override
    protected void addTranslations() {
        // Mod & Config Title
        add("modmenu.name.tessera", "Tessera");
        add("modmenu.description.tessera", "Motor nativo de optimización de VRAM y compresión de texturas a BC7 en GPU.");

        // Títulos de pantalla de configuración requeridos por NeoForge
        add("tessera.configuration.title", "Configuración de Tessera");
        add("tessera.configuration.section.tessera.client.toml", "Configuración de Cliente de Tessera");
        add("tessera.configuration.section.tessera.client.toml.title", "Configuración de Cliente de Tessera");

        // Categorías (Título, Tooltip y Botones de submenú)
        addConfigCategory("compression", "Compresión", "Ajustes de compresión nativa BC7 y congelación de animaciones.", "Ajustes de Compresión");
        addConfigCategory("deduplication", "Desduplicación", "Ajustes de hashing perceptual y fusión de sprites duplicados.", "Ajustes de Desduplicación");
        addConfigCategory("vramBudget", "Límite de VRAM", "Ajustes de límites de memoria VRAM y reducción de calidad.", "Ajustes de Límite de VRAM");
        addConfigCategory("cache", "Caché", "Ajustes de la ubicación del almacenamiento en disco.", "Ajustes de Caché");

        // Opciones de Compresión
        addConfigOption("compressionQuality", "Calidad de Compresión",
                "Preajuste de calidad BC7 (0: Más rápido a 7: Máxima fidelidad). Controla la calidad de compresión contra el tiempo de procesamiento.");
        addConfigOption("disableNativeCompression", "Desactivar Compresión Nativa",
                "Fuerza el comportamiento RGBA vanilla aunque la compresión BC7 esté soportada por la GPU.");
        addConfigOption("disableAnimationsForMaxVramSavings", "Congelar Animaciones (Máximo Ahorro)",
                "Congela las animaciones de texturas (agua, lava, portales, GUIs) para permitir forzar compresión BC7 en atlas gigantes como blocks.png y gui.png.");

        // Opciones de Desduplicación
        addConfigOption("dedupSimilarityThreshold", "Umbral de Similitud",
                "Distancia Hamming (0-64) para huellas pHash de 64 bits. Valores más bajos son más estrictos al fusionar sprites duplicados.");

        // Opciones de Límite de VRAM
        addConfigOption("vramBudgetTargetMb", "Objetivo de VRAM (MB)",
                "Límite orientativo de memoria VRAM en megabytes evaluado durante el empaquetado de atlas.");
        addConfigOption("maxQualityStepDownAttempts", "Intentos de Reducción de Calidad",
                "Número máximo de intentos de reducción de calidad ejecutados para mantenerse dentro del límite de VRAM.");

        // Opciones de Caché
        addConfigOption("cacheDirectory", "Directorio de Caché",
                "Nombre de la carpeta donde se almacenan en disco los bloques comprimidos en BC7.");

        // Debug Overlay
        add("tessera.overlay.vram_saved", "VRAM Ahorrada por Tessera: %s MB (BC7 Activo)");
    }
}