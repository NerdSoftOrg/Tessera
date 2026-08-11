package com.nerdsoft.mods.tessera.cache;

import com.nerdsoft.mods.tessera.jni.NativeBridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Optional;

public final class AtlasCache {

    private static final byte[] MAGIC = {'T', 'S', 'R', '2'};
    private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES * 5;
    private final Path cacheDirectory;

    public AtlasCache(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public String hashHex(ByteBuffer rgba8Direct, int length) {
        byte[] digest = NativeBridge.hashContent(rgba8Direct, length);
        return HexFormat.of().formatHex(digest);
    }

    /**
     * Reads a cached compressed atlas, scoped to a specific {@link CompressedFormat}.
     *
     * <p>The cache key is content-hash-based (see {@link #hashHex}), which only
     * covers pixel data -- it says nothing about which GL format the caller
     * wants that content encoded to. The same RGBA8 pixels can legitimately
     * be requested as BC1 for one atlas bucket and BC7 for another (e.g. a
     * sprite whose opaque/alpha classification differs between mod-added
     * variants sharing identical pixels), so {@code format} is threaded
     * through both the read and write paths and is checked against what's
     * on disk before a hit is returned -- a format mismatch is treated as a
     * cache miss, never as a silently-wrong-format hit.
     */
    public Optional<CachedAtlas> read(String hashHex, CompressedFormat format) throws IOException {
        Path file = fileFor(hashHex, format);
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

        int formatOrdinal = header.getInt();
        if (formatOrdinal != format.ordinal()) {
            // Same content hash, different requested format (or a stale
            // cache file from before format-tagging existed) -- treat as a
            // miss rather than returning bytes in the wrong GL format.
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

    // False Positive
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void write(String hashHex, CompressedFormat format, int width, int height, int qualityPreset, ByteBuffer compressedBlocks) throws IOException {
        Files.createDirectories(cacheDirectory);

        int payloadLength = compressedBlocks.remaining();
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + payloadLength).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC);
        out.putInt(format.ordinal());
        out.putInt(width);
        out.putInt(height);
        out.putInt(qualityPreset);
        out.putInt(payloadLength);

        ByteBuffer sourceDuplicate = compressedBlocks.duplicate();
        out.put(sourceDuplicate);
        out.flip();

        Path tempFile = Files.createTempFile(cacheDirectory, hashHex, ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (out.hasRemaining()) {
                    channel.write(out);
                }
            }
            Files.move(tempFile, fileFor(hashHex, format), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    private Path fileFor(String hashHex, CompressedFormat format) {
        return cacheDirectory.resolve(hashHex + format.fileExtension());
    }

    /**
     * Which GPU block-compression format a cached blob holds. Determines
     * both the on-disk file extension (so BC1/BC7 caches for the same
     * content hash never collide on the filesystem either) and the
     * in-header discriminator checked by {@link #read}.
     */
    public enum CompressedFormat {
        BC1(".bc1"),
        BC7(".bc7");

        private final String extension;

        CompressedFormat(String extension) {
            this.extension = extension;
        }

        public String fileExtension() {
            return extension;
        }
    }

    public record CachedAtlas(int width, int height, int qualityPreset, ByteBuffer compressedBlocks) {
    }
}