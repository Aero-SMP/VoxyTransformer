package com.aerosmp.inittransformer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** Build-only checks using the payload extracted from the finished transformer. */
public final class XorPatchVerification {
    public static void main(String[] args) throws Exception {
        byte[] packed;
        try (JarFile jar = new JarFile(args[0])) {
            require(jar.getJarEntry(XorPatch.RESOURCE) != null, "Packaged XOR resource is missing");
            try (var input = jar.getInputStream(jar.getJarEntry(XorPatch.RESOURCE))) {
                packed = input.readAllBytes();
            }
            require(jar.stream().noneMatch(entry -> entry.getName().endsWith(".aitp")
                    || entry.getName().contains("GenerateVoxyPatch")
                    || entry.getName().contains("PatchTool")
                    || entry.getName().contains("Verification")), "Build tooling or old patches shipped in the JAR");
            var service = jar.getJarEntry("META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator");
            require(service != null, "Early locator service is missing");
            try (var input = jar.getInputStream(service)) {
                require(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim()
                        .equals("com.aerosmp.inittransformer.VoxyCandidateLocator"), "Incorrect locator service");
            }
        }

        Path root = Files.createTempDirectory("voxy-1.1-verification-");
        System.out.println("Verification files: " + root);
        String sourceName;
        String targetName;
        try (var header = new DataInputStream(new ByteArrayInputStream(packed))) {
            header.readInt();
            header.readInt();
            sourceName = header.readUTF();
            targetName = header.readUTF();
        }
        Path game = Files.createDirectory(root.resolve("game"));
        Path source = Files.createDirectories(game.resolve("mods")).resolve(sourceName);
        Path output = game.resolve(".aerosmp/voxy").resolve(targetName);
        Path expected = Path.of(args[2]);
        byte[] original = Files.readAllBytes(Path.of(args[1]));
        XorPatch patch = read(packed);

        fails(() -> patch.prepare(game), "Missing Voxy source");
        Files.write(source, original);
        require(patch.prepare(game).equals(output), "Incorrect target path");
        require(Files.mismatch(output, expected) == -1, "Packaged patch does not reconstruct the exact target");
        require(Arrays.equals(Files.readAllBytes(source), original), "Source was modified");

        Files.setLastModifiedTime(output, FileTime.fromMillis(123456000));
        FileTime cachedTime = Files.getLastModifiedTime(output);
        Files.delete(source);
        require(patch.prepare(game).equals(output), "Cache requires absent source");
        require(Files.getLastModifiedTime(output).equals(cachedTime), "Valid cache was rewritten");
        Files.write(source, new byte[] {0});
        patch.prepare(game); // A valid target also ignores an invalid source.

        byte[] damagedTarget = Files.readAllBytes(output);
        damagedTarget[0] ^= 1;
        Files.write(output, damagedTarget);
        fails(() -> patch.prepare(game), "source checksum mismatch");
        byte[] damagedSource = original.clone();
        damagedSource[0] ^= 1;
        Files.write(source, damagedSource);
        fails(() -> patch.prepare(game), "source checksum mismatch");
        require(Arrays.equals(Files.readAllBytes(output), damagedTarget), "Failed reconstruction replaced the cache");
        Files.write(source, original);

        byte[] damagedPatch = packed.clone();
        damagedPatch[damagedPatch.length - 1] ^= 1;
        fails(() -> read(damagedPatch).prepare(game), "Reconstructed Voxy checksum mismatch");
        require(Arrays.equals(Files.readAllBytes(output), damagedTarget), "Invalid payload replaced the cache");
        patch.prepare(game);
        require(Files.mismatch(output, expected) == -1, "Same-size corrupted cache was not repaired");
        Files.write(output, new byte[] {0});
        patch.prepare(game);
        require(Files.mismatch(output, expected) == -1, "Truncated cache was not repaired");

        Files.delete(output);
        Files.createDirectory(output);
        Path blocker = Files.writeString(output.resolve("keep.txt"), "preserve");
        fails(() -> patch.prepare(game), null);
        require(Files.readString(blocker).equals("preserve"), "Failed replacement damaged existing data");
        try (var files = Files.list(output.getParent())) {
            require(files.noneMatch(path -> path.toString().endsWith(".tmp")), "Temporary file leaked after move failure");
        }

        fails(() -> XorPatch.read(null), "Missing embedded");
        fails(() -> read(Arrays.copyOf(packed, packed.length - 1)), "Truncated");
        fails(() -> read(Arrays.copyOf(packed, packed.length + 1)), "trailing");
        byte[] badVersion = packed.clone();
        badVersion[7] ^= 1;
        fails(() -> read(badVersion), "Unsupported");

        checkSizes(root.resolve("same"), new byte[] {1, 2, 3}, new byte[] {3, 2, 1});
        checkSizes(root.resolve("grow"), new byte[] {1}, new byte[] {2, 0, 4});
        checkSizes(root.resolve("shrink"), new byte[] {1, 2, 3}, new byte[] {4});
        System.out.println("Verified: exact packaged target, cache reuse without source, cache repair, source/payload rejection,");
        System.out.println("safe replacement failure, temporary-file cleanup, and equal/growing/shrinking XOR reconstruction.");
    }

    private static void checkSizes(Path directory, byte[] sourceBytes, byte[] targetBytes) throws Exception {
        Files.createDirectories(directory.resolve("mods"));
        Path source = Files.write(directory.resolve("mods/source.jar.source"), sourceBytes);
        Path target = Files.write(directory.resolve("target.jar"), targetBytes);
        Path payload = directory.resolve("voxy.xor");
        String hash = HexFormat.of().formatHex(XorPatch.sha256(sourceBytes));
        GenerateVoxyPatch.main(new String[] {source.toString(), target.toString(), "source.jar.source", hash, payload.toString()});
        Path output = read(Files.readAllBytes(payload)).prepare(directory);
        require(Files.mismatch(output, target) == -1, "XOR length case failed: " + directory);
        fails(() -> GenerateVoxyPatch.main(new String[] {source.toString(), target.toString(),
                "source.jar.source", "00".repeat(32), payload.toString()}), "pinned SHA-256");
    }

    private static XorPatch read(byte[] bytes) throws IOException {
        return XorPatch.read(new ByteArrayInputStream(bytes));
    }

    private static void fails(Operation operation, String message) throws Exception {
        try {
            operation.run();
        } catch (IOException expected) {
            require(message == null || expected.getMessage().contains(message), "Unexpected failure: " + expected);
            return;
        }
        throw new AssertionError("Expected failure: " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }
}
