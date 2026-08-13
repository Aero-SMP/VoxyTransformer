package com.aerosmp.inittransformer.patch;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class PatchTool {
    private PatchTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }

        switch (args[0]) {
            case "generate" -> generate(args);
            case "inspect" -> inspect(args);
            case "validate" -> validate(args);
            case "apply" -> apply(args);
            case "replace-after-exit" -> replaceAfterExit(args);
            case "self-test" -> selfTest();
            default -> usage();
        }
    }

    private static void generate(String[] args) throws IOException {
        if (args.length < 6 || args.length > 7) {
            throw new IllegalArgumentException("generate requires <source.jar> <target.jar> <out.patch> <modId> <displayName> [--allow-empty]");
        }

        Path source = Path.of(args[1]);
        Path target = Path.of(args[2]);
        Path output = Path.of(args[3]);
        String modId = args[4];
        String displayName = args[5];
        boolean allowEmpty = args.length == 7 && "--allow-empty".equals(args[6]);

        BinaryPatch patch = BinaryPatch.generate(Files.readAllBytes(source), Files.readAllBytes(target), modId, displayName);
        if (patch.isNoop() && !allowEmpty) {
            throw new IOException("Refusing to write a no-op patch because source and target are identical. Pass --allow-empty only for scaffold/testing artifacts.");
        }
        patch.write(output);
        System.out.println("Wrote " + output);
        System.out.println(patch.describe());
    }

    private static void inspect(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("inspect requires <patch>");
        }
        System.out.println(BinaryPatch.read(Path.of(args[1])).describe());
    }

    private static void validate(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("validate requires <patch> [--allow-empty]");
        }

        boolean allowEmpty = args.length == 3 && "--allow-empty".equals(args[2]);
        BinaryPatch patch = BinaryPatch.read(Path.of(args[1]));
        if (patch.isNoop() && !allowEmpty) {
            throw new IOException("Patch artifact is a no-op. Regenerate it from distinct source and target jars, or pass --allow-empty only for scaffold/testing builds.");
        }
        System.out.println("Validated " + patch.describe());
    }

    private static void apply(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("apply requires <source.jar> <patch> <out.jar>");
        }

        Path source = Path.of(args[1]);
        Path patchPath = Path.of(args[2]);
        Path output = Path.of(args[3]);
        BinaryPatch.read(patchPath).apply(source, output);
        System.out.println("Wrote " + output);
    }

    private static void replaceAfterExit(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("replace-after-exit requires <pid> <pending.jar> <target.jar>");
        }

        long pid = Long.parseLong(args[1]);
        Path pending = Path.of(args[2]);
        Path target = Path.of(args[3]);

        ProcessHandle.of(pid).ifPresent(handle -> {
            while (handle.isAlive()) {
                sleep(250);
            }
        });

        IOException last = null;
        for (int attempt = 1; attempt <= 240; attempt++) {
            try {
                moveReplacing(pending, target);
                System.out.println("Installed staged patch at " + target);
                return;
            } catch (IOException e) {
                last = e;
                sleep(250);
            }
        }

        throw new IOException("Timed out replacing " + target + " with " + pending, last);
    }

    private static void selfTest() throws IOException {
        runCase(
                "same-size",
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
                new byte[] {1, 2, 9, 9, 5, 6, 7, 0});
        runCase(
                "resize",
                "source-prefix-middle-suffix".getBytes(),
                "source-prefix-expanded-middle-suffix".getBytes());
        System.out.println("PatchTool self-test passed");
    }

    private static void runCase(String name, byte[] source, byte[] target) throws IOException {
        BinaryPatch patch = BinaryPatch.generate(source, target, "testmod", "Test " + name);
        if (source.length == target.length && patch.hunks().stream().noneMatch(hunk -> hunk.mode() == BinaryPatch.HunkMode.XOR)) {
            throw new IOException("Self-test failed for " + name + ": expected at least one XOR hunk");
        }
        byte[] applied = patch.apply(source);
        if (!Arrays.equals(target, applied)) {
            throw new IOException("Self-test failed for " + name);
        }
    }

    public static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  generate <source.jar> <target.jar> <out.patch> <modId> <displayName>
                  generate <source.jar> <target.jar> <out.patch> <modId> <displayName> --allow-empty
                  inspect <patch>
                  validate <patch>
                  validate <patch> --allow-empty
                  apply <source.jar> <patch> <out.jar>
                  replace-after-exit <pid> <pending.jar> <target.jar>
                  self-test
                """);
    }
}
