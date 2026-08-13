package com.aerosmp.inittransformer;

import com.aerosmp.inittransformer.patch.BinaryPatch;
import cpw.mods.modlauncher.api.IEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates and registers the AeroSMP Voxy fork before normal mod discovery. */
public final class VoxyCandidateLocator implements IModFileCandidateLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyCandidateLocator.class);
    private static final String SOURCE_NAME = "voxy-0.2.9-alpha-1.21.11.jar.source";
    private static final String OUTPUT_NAME = "voxy-0.2.15-beta+1.21.1-neoforge.jar";
    private static final String PATCH_RESOURCE =
            "aerosmp/inittransformer/patches/voxy-0.2.15-beta-neoforge.aitp";

    @Override
    public int getPriority() {
        return HIGHEST_SYSTEM_PRIORITY + 100;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        Path gameDirectory = context.environment().getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElseThrow(() -> new IllegalStateException("NeoForge did not provide the game directory"));
        Path source = gameDirectory.resolve("mods").resolve(SOURCE_NAME);
        Path output = gameDirectory.resolve(".aerosmp").resolve("voxy").resolve(OUTPUT_NAME);

        try {
            BinaryPatch patch = loadPatch();
            if (isTarget(patch, output)) {
                LOGGER.info("Using cached AeroSMP Voxy jar {}", output);
            } else {
                createTarget(patch, source, output);
            }

            if (pipeline.addPath(output, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR).isEmpty()) {
                throw new IOException("NeoForge did not accept generated Voxy jar " + output);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare AeroSMP Voxy without a restart: " + e.getMessage(), e);
        }
    }

    private static BinaryPatch loadPatch() throws IOException {
        try (InputStream input = VoxyCandidateLocator.class.getClassLoader().getResourceAsStream(PATCH_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing embedded patch " + PATCH_RESOURCE);
            }
            BinaryPatch patch = BinaryPatch.read(input);
            if (patch.isNoop()) {
                throw new IOException("Embedded Voxy patch is empty");
            }
            return patch;
        }
    }

    private static void createTarget(BinaryPatch patch, Path source, Path output) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Missing Modrinth Voxy source " + source);
        }
        long sourceSize = Files.size(source);
        byte[] sourceHash = BinaryPatch.sha256(source);
        if (!patch.matchesSource(sourceSize, sourceHash)) {
            throw new IOException("Voxy source checksum mismatch at " + source + ": "
                    + HexFormat.of().formatHex(sourceHash));
        }

        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), OUTPUT_NAME + ".", ".tmp");
        try {
            patch.apply(source, temporary);
            if (!isTarget(patch, temporary)) {
                throw new IOException("Generated Voxy jar failed target checksum verification");
            }
            moveAtomically(temporary, output);
        } finally {
            Files.deleteIfExists(temporary);
        }
        LOGGER.info("Generated AeroSMP Voxy jar {} from {}", output, source);
    }

    private static boolean isTarget(BinaryPatch patch, Path path) throws IOException {
        return Files.isRegularFile(path) && patch.matchesTarget(Files.size(path), BinaryPatch.sha256(path));
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public String toString() {
        return "AeroSMP Voxy source locator";
    }
}
