package com.nerdsoft.mods.tessera.cache;

import com.nerdsoft.mods.tessera.jni.NativeBridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.Optional;

public final class AtlasCache {

    private static final byte[] MAGIC = {'T', 'S', 'R', '1'};
    private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES * 4;
    private final Path cacheDirectory;

    public AtlasCache(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    private static byte[] toArray(ByteBuffer buffer) {
        byte[] array = new byte[buffer.remaining()];
        buffer.get(array);
        return array;
    }

    public String hashHex(ByteBuffer rgba8Direct, int length) {
        byte[] digest = NativeBridge.hashContent(rgba8Direct, length);
        return HexFormat.of().formatHex(digest);
    }

    public Optional<CachedAtlas> read(String hashHex) throws IOException {
        Path file = fileFor(hashHex);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        byte[] raw = Files.readAllBytes(file);
        if (raw.length < HEADER_BYTES) {
            return Optional.empty();
        }

        ByteBuffer header = ByteBuffer.wrap(raw, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        header.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            return Optional.empty();
        }

        int width = header.getInt();
        int height = header.getInt();
        int qualityPreset = header.getInt();
        int payloadLength = header.getInt();

        if (raw.length < HEADER_BYTES + payloadLength) {
            return Optional.empty();
        }

        ByteBuffer payload = ByteBuffer.allocateDirect(payloadLength);
        payload.put(raw, HEADER_BYTES, payloadLength);
        payload.flip();

        return Optional.of(new CachedAtlas(width, height, qualityPreset, payload));
    }

    public void write(String hashHex, int width, int height, int qualityPreset, ByteBuffer compressedBlocks) throws IOException {
        Files.createDirectories(cacheDirectory);

        int payloadLength = compressedBlocks.remaining();
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + payloadLength).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC);
        out.putInt(width);
        out.putInt(height);
        out.putInt(qualityPreset);
        out.putInt(payloadLength);

        ByteBuffer sourceDuplicate = compressedBlocks.duplicate();
        out.put(sourceDuplicate);
        out.flip();

        Path tempFile = Files.createTempFile(cacheDirectory, hashHex, ".tmp");
        try {
            Files.write(tempFile, toArray(out));
            Files.move(tempFile, fileFor(hashHex), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    private Path fileFor(String hashHex) {
        return cacheDirectory.resolve(hashHex + ".bc7");
    }

    public record CachedAtlas(int width, int height, int qualityPreset, ByteBuffer compressedBlocks) {
    }
}
