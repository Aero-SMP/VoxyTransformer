package com.aerosmp.inittransformer.patch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class BinaryPatch {
    private static final int MAGIC = 0x41495450; // AITP
    private static final int VERSION = 2;
    private static final int SHA256_LENGTH = 32;

    private final String targetModId;
    private final String displayName;
    private final long sourceSize;
    private final long targetSize;
    private final byte[] sourceSha256;
    private final byte[] targetSha256;
    private final List<Hunk> hunks;

    public BinaryPatch(
            String targetModId,
            String displayName,
            long sourceSize,
            long targetSize,
            byte[] sourceSha256,
            byte[] targetSha256,
            List<Hunk> hunks) {
        this.targetModId = requireText(targetModId, "targetModId");
        this.displayName = requireText(displayName, "displayName");
        this.sourceSize = requireNonNegative(sourceSize, "sourceSize");
        this.targetSize = requireNonNegative(targetSize, "targetSize");
        this.sourceSha256 = requireSha(sourceSha256, "sourceSha256");
        this.targetSha256 = requireSha(targetSha256, "targetSha256");
        this.hunks = List.copyOf(Objects.requireNonNull(hunks, "hunks"));
        validateHunks(this.hunks, this.sourceSize);
    }

    public String targetModId() {
        return targetModId;
    }

    public String displayName() {
        return displayName;
    }

    public long sourceSize() {
        return sourceSize;
    }

    public long targetSize() {
        return targetSize;
    }

    public byte[] sourceSha256() {
        return sourceSha256.clone();
    }

    public byte[] targetSha256() {
        return targetSha256.clone();
    }

    public List<Hunk> hunks() {
        return hunks;
    }

    public boolean matchesSource(long size, byte[] sha256) {
        return size == sourceSize && Arrays.equals(sourceSha256, sha256);
    }

    public boolean matchesTarget(long size, byte[] sha256) {
        return size == targetSize && Arrays.equals(targetSha256, sha256);
    }

    public boolean isNoop() {
        return sourceSize == targetSize && Arrays.equals(sourceSha256, targetSha256) && hunks.isEmpty();
    }

    public String sourceSha256Hex() {
        return hex(sourceSha256);
    }

    public String targetSha256Hex() {
        return hex(targetSha256);
    }

    public static BinaryPatch read(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return read(input);
        }
    }

    public static BinaryPatch read(InputStream input) throws IOException {
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
            int magic = data.readInt();
            if (magic != MAGIC) {
                throw new IOException("Not an AeroSMP init transformer patch");
            }

            int version = data.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported patch version " + version);
            }

            String targetModId = data.readUTF();
            String displayName = data.readUTF();
            long sourceSize = data.readLong();
            long targetSize = data.readLong();
            byte[] sourceSha256 = data.readNBytes(SHA256_LENGTH);
            byte[] targetSha256 = data.readNBytes(SHA256_LENGTH);
            if (sourceSha256.length != SHA256_LENGTH || targetSha256.length != SHA256_LENGTH) {
                throw new IOException("Truncated SHA-256 digest in patch");
            }

            int hunkCount = data.readInt();
            if (hunkCount < 0) {
                throw new IOException("Negative hunk count");
            }

            List<Hunk> hunks = new ArrayList<>(hunkCount);
            for (int i = 0; i < hunkCount; i++) {
                long offset = data.readLong();
                HunkMode mode = HunkMode.fromId(data.readUnsignedByte());
                int removeLength = data.readInt();
                int payloadLength = data.readInt();
                if (removeLength < 0 || payloadLength < 0) {
                    throw new IOException("Negative hunk length at index " + i);
                }
                byte[] payload = data.readNBytes(payloadLength);
                if (payload.length != payloadLength) {
                    throw new IOException("Truncated hunk payload at index " + i);
                }
                hunks.add(new Hunk(mode, offset, removeLength, payload));
            }

            return new BinaryPatch(targetModId, displayName, sourceSize, targetSize, sourceSha256, targetSha256, hunks);
        }
    }

    public void write(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream output = Files.newOutputStream(path)) {
            write(output);
        }
    }

    public void write(OutputStream output) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output))) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeUTF(targetModId);
            data.writeUTF(displayName);
            data.writeLong(sourceSize);
            data.writeLong(targetSize);
            data.write(sourceSha256);
            data.write(targetSha256);
            data.writeInt(hunks.size());
            for (Hunk hunk : hunks) {
                data.writeLong(hunk.offset());
                data.writeByte(hunk.mode().id());
                data.writeInt(hunk.removeLength());
                data.writeInt(hunk.payload().length);
                data.write(hunk.payload());
            }
        }
    }

    public static BinaryPatch generate(byte[] source, byte[] target, String targetModId, String displayName) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        List<Hunk> hunks;
        if (source.length == target.length) {
            hunks = generateSameSizeHunks(source, target);
        } else {
            hunks = generateOffsetAlignedResizeHunks(source, target);
        }

        return new BinaryPatch(
                targetModId,
                displayName,
                source.length,
                target.length,
                sha256(source),
                sha256(target),
                hunks);
    }

    public byte[] apply(byte[] source) throws IOException {
        Objects.requireNonNull(source, "source");
        byte[] sourceHash = sha256(source);
        if (!matchesSource(source.length, sourceHash)) {
            throw new IOException("Source checksum mismatch. Expected " + sourceSha256Hex() + " but got " + hex(sourceHash));
        }

        if (targetSize > Integer.MAX_VALUE) {
            throw new IOException("Target is too large to materialize in memory");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream((int) targetSize);
        int cursor = 0;
        for (Hunk hunk : hunks) {
            int offset = checkedInt(hunk.offset(), "hunk offset");
            if (offset < cursor) {
                throw new IOException("Overlapping hunk at offset " + offset);
            }
            output.write(source, cursor, offset - cursor);
            byte[] payload = hunk.payload();
            switch (hunk.mode()) {
                case REPLACE -> output.write(payload);
                case XOR -> {
                    if (hunk.removeLength() != payload.length) {
                        throw new IOException("XOR hunk length mismatch at offset " + hunk.offset());
                    }
                    for (int i = 0; i < payload.length; i++) {
                        output.write(source[offset + i] ^ payload[i]);
                    }
                }
            }
            cursor = offset + hunk.removeLength();
        }
        output.write(source, cursor, source.length - cursor);

        byte[] target = output.toByteArray();
        byte[] targetHash = sha256(target);
        if (target.length != targetSize || !Arrays.equals(targetSha256, targetHash)) {
            throw new IOException("Target checksum mismatch after applying patch. Expected " + targetSha256Hex() + " but got " + hex(targetHash));
        }
        return target;
    }

    public void apply(Path sourcePath, Path outputPath) throws IOException {
        byte[] source = Files.readAllBytes(sourcePath);
        byte[] target = apply(source);
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(outputPath, target);
    }

    public String describe() {
        long xorHunks = hunks.stream().filter(hunk -> hunk.mode() == HunkMode.XOR).count();
        long replaceHunks = hunks.size() - xorHunks;
        String noOpSuffix = isNoop() ? ", no-op" : "";
        return displayName + " [" + targetModId + "] source=" + sourceSha256Hex() + " (" + sourceSize + " bytes), target=" + targetSha256Hex() + " (" + targetSize + " bytes), hunks=" + hunks.size() + " (xor=" + xorHunks + ", replace=" + replaceHunks + noOpSuffix + ")";
    }

    public static byte[] sha256(Path path) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    public static byte[] sha256(byte[] bytes) {
        return newSha256().digest(bytes);
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static List<Hunk> generateSameSizeHunks(byte[] source, byte[] target) {
        List<Hunk> hunks = new ArrayList<>();
        int index = 0;
        while (index < source.length) {
            while (index < source.length && source[index] == target[index]) {
                index++;
            }
            if (index >= source.length) {
                break;
            }
            int start = index;
            while (index < source.length && source[index] != target[index]) {
                index++;
            }
            byte[] xorBytes = new byte[index - start];
            for (int i = 0; i < xorBytes.length; i++) {
                xorBytes[i] = (byte) (source[start + i] ^ target[start + i]);
            }
            hunks.add(new Hunk(HunkMode.XOR, start, index - start, xorBytes));
        }
        return hunks;
    }

    private static List<Hunk> generateOffsetAlignedResizeHunks(byte[] source, byte[] target) {
        List<Hunk> hunks = new ArrayList<>(generateSameSizeHunks(
                Arrays.copyOf(source, Math.min(source.length, target.length)),
                Arrays.copyOf(target, Math.min(source.length, target.length))));

        if (target.length > source.length) {
            hunks.add(new Hunk(HunkMode.REPLACE, source.length, 0, Arrays.copyOfRange(target, source.length, target.length)));
        } else if (source.length > target.length) {
            hunks.add(new Hunk(HunkMode.REPLACE, target.length, source.length - target.length, new byte[0]));
        }

        return hunks;
    }

    private static void validateHunks(List<Hunk> hunks, long sourceSize) {
        long cursor = 0;
        for (Hunk hunk : hunks) {
            if (hunk.offset() < cursor) {
                throw new IllegalArgumentException("Overlapping or unsorted hunk at offset " + hunk.offset());
            }
            if (hunk.removeLength() < 0) {
                throw new IllegalArgumentException("Negative hunk remove length");
            }
            if (hunk.mode() == HunkMode.XOR && hunk.removeLength() != hunk.payload().length) {
                throw new IllegalArgumentException("XOR hunk payload length must match removed length");
            }
            long end = hunk.offset() + hunk.removeLength();
            if (end > sourceSize) {
                throw new IllegalArgumentException("Hunk exceeds source size at offset " + hunk.offset());
            }
            cursor = end;
        }
    }

    private static int checkedInt(long value, String label) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException(label + " is outside supported range: " + value);
        }
        return (int) value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static long requireNonNegative(long value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must not be negative");
        }
        return value;
    }

    private static byte[] requireSha(byte[] value, String label) {
        Objects.requireNonNull(value, label);
        if (value.length != SHA256_LENGTH) {
            throw new IllegalArgumentException(label + " must be 32 bytes");
        }
        return value.clone();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public enum HunkMode {
        REPLACE(0),
        XOR(1);

        private final int id;

        HunkMode(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        static HunkMode fromId(int id) throws IOException {
            for (HunkMode mode : values()) {
                if (mode.id == id) {
                    return mode;
                }
            }
            throw new IOException("Unknown hunk mode " + id);
        }
    }

    public record Hunk(HunkMode mode, long offset, int removeLength, byte[] payload) {
        public Hunk {
            mode = Objects.requireNonNull(mode, "mode");
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            if (removeLength < 0) {
                throw new IllegalArgumentException("removeLength must not be negative");
            }
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
