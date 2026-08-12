package com.nerdsoft.mods.tessera.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class NativeLibraryLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/NativeLibraryLoader");

    private static volatile boolean available;
    private static volatile boolean initialized;

    private NativeLibraryLoader() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        available = tryLoad();
        if (!available) {
            LOGGER.warn("Tessera native compression bridge unavailable; falling back to vanilla atlas behavior.");
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    private static boolean tryLoad() {
        String resourcePath = resolveResourcePath();
        if (resourcePath == null) {
            LOGGER.warn(
                    "No Tessera native bridge is bundled for platform {} ({}).",
                    System.getProperty("os.name"), System.getProperty("os.arch")
            );
            return false;
        }

        try {
            Path extracted = extractToTempFile(resourcePath);
            System.load(extracted.toAbsolutePath().toString());
        } catch (IOException | UnsatisfiedLinkError e) {
            LOGGER.warn("Failed to load the Tessera native compression bridge from {}.", resourcePath, e);
            return false;
        }

        try {
            return NativeBridge.isNativeAvailable();
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Tessera native library loaded but its JNI symbols could not be resolved.", e);
            return false;
        }
    }

    private static String resolveResourcePath() {
        String platformDir = platformDirectory();
        if (platformDir == null) {
            return null;
        }
        String libraryFileName = libraryFileNameFor(platformDir);

        // src/main/resources/assets/tessera/natives/<platform>/<lib>
        return "/assets/tessera/natives/" + platformDir + "/" + libraryFileName;
    }

    private static String libraryFileNameFor(String platformDir) {
        if (platformDir.startsWith("windows")) {
            return "tessera_bridge.dll";
        }
        return "libtessera_bridge.so";
    }

    private static String platformDirectory() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isAarch64 = archName.contains("aarch64") || archName.contains("arm64");

        // macOS is not supported due to OpenGL 4.1 limitations
        if (osName.contains("mac") || osName.contains("darwin")) {
            return null;
        }

        if (osName.contains("win")) {
            return isAarch64 ? null : "windows-x86_64";
        }
        if (osName.contains("linux")) {
            return isAarch64 ? "linux-aarch64" : "linux-x86_64";
        }
        return null;
    }

    private static Path extractToTempFile(String resourcePath) throws IOException {
        String suffix = resourcePath.substring(resourcePath.lastIndexOf('.'));
        Path tempFile = Files.createTempFile("tessera-bridge-", suffix);
        tempFile.toFile().deleteOnExit();

        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }
        }

        return tempFile;
    }
}
