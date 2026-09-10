package com.aerosmp.inittransformer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** One pinned source, one XOR stream, and one verified cached target. */
final class XorPatch {
    static final String RESOURCE = "aerosmp/voxy.xor";
    static final int MAGIC = 0x56584F52; // VXOR
    static final int VERSION = 1;

    private final String sourceName;
    private final String targetName;
    private final int sourceSize;
    private final byte[] sourceHash;
    private final byte[] targetHash;
    private final byte[] xor;

    private XorPatch(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            throw new IOException("Unsupported Voxy XOR resource");
        }
        sourceName = readFilename(input);
        targetName = readFilename(input);
        sourceSize = input.readInt();
        int targetSize = input.readInt();
        if (sourceSize < 0 || targetSize < 0) {
            throw new IOException("Negative Voxy JAR size");
        }
        sourceHash = readExactly(input, 32);
        targetHash = readExactly(input, 32);
        xor = readExactly(input, targetSize);
        if (input.read() != -1) {
            throw new IOException("Unexpected trailing Voxy XOR data");
        }
    }

    static XorPatch read(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("Missing embedded " + RESOURCE);
        }
        try (DataInputStream data = new DataInputStream(input)) {
            return new XorPatch(data);
        }
    }

    Path prepare(Path gameDirectory) throws IOException {
        Path output = gameDirectory.resolve(".aerosmp/voxy").resolve(targetName);
        if (matchesTarget(output)) {
            return output;
        }

        Path source = gameDirectory.resolve("mods").resolve(sourceName);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Missing Voxy source " + source);
        }
        byte[] original = Files.readAllBytes(source);
        if (original.length != sourceSize || !Arrays.equals(sha256(original), sourceHash)) {
            throw new IOException("Voxy source checksum mismatch at " + source);
        }
        byte[] target = xor.clone();
        for (int i = 0; i < Math.min(original.length, target.length); i++) {
            target[i] ^= original[i];
        }
        if (!Arrays.equals(sha256(target), targetHash)) {
            throw new IOException("Reconstructed Voxy checksum mismatch");
        }

        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), targetName + ".", ".tmp");
        try {
            Files.write(temporary, target);
            if (!matchesTarget(temporary)) {
                throw new IOException("Written Voxy JAR failed checksum verification");
            }
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return output;
    }

    private boolean matchesTarget(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) == xor.length
                && Arrays.equals(sha256(Files.readAllBytes(path)), targetHash);
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] readExactly(DataInputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated Voxy XOR resource");
        }
        return bytes;
    }

    private static String readFilename(DataInputStream input) throws IOException {
        String name = input.readUTF();
        if (name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains(":")) {
            throw new IOException("Invalid Voxy filename in XOR resource");
        }
        return name;
    }
}
