package com.aerosmp.inittransformer;

import com.aerosmp.inittransformer.patch.BinaryPatch;
import com.aerosmp.inittransformer.patch.PatchTool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(AeroSmpInitTransformer.MOD_ID)
public final class AeroSmpInitTransformer {
    public static final String MOD_ID = "aerosmp_init_transformer";

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PATCH_INDEX = "aerosmp/inittransformer/patches/index.txt";
    private static final String PATCH_DIR = "aerosmp/inittransformer/patches/";
    private static final String OVERRIDE_JAR_PROPERTY = "aerosmp.inittransformer.voxyJar";
    private static final String INSTALL_JAR_PROPERTY = "aerosmp.inittransformer.installJar";
    private static final String SOURCE_STAGING_SUFFIX = ".aerosmp-source";

    public AeroSmpInitTransformer() {
        try {
            run();
        } catch (RestartRequiredException e) {
            LOGGER.warn(e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AeroSMP Init Transformer failed: " + e.getMessage(), e);
        }
    }

    private static void run() throws IOException {
        List<BinaryPatch> patches = loadPatches();
        if (patches.isEmpty()) {
            LOGGER.warn("No AeroSMP init transformer patch artifacts were bundled");
            return;
        }

        List<String> missing = new ArrayList<>();
        for (BinaryPatch patch : patches) {
            PatchCandidate candidate = findCandidate(patch);
            if (candidate == null) {
                missing.add(patch.displayName() + " source=" + patch.sourceSha256Hex() + " target=" + patch.targetSha256Hex());
                continue;
            }

            if (candidate.state() == CandidateState.TARGET) {
                LOGGER.info("{} already matches AeroSMP target checksum at {}", patch.displayName(), candidate.path());
                return;
            }

            PatchInstallResult result = installPatch(patch, candidate.path(), candidate.installPath());
            String message = switch (result.state()) {
                case REPLACED -> "AeroSMP patched " + patch.displayName() + " at " + result.installedPath() + ". Restart Minecraft now.";
                case STAGED -> "AeroSMP staged a patch for " + patch.displayName() + " at " + result.installedPath()
                        + ". Close this Minecraft process, wait for the staged replacement, then restart.";
            };
            throw new RestartRequiredException(message);
        }

        throw new IllegalStateException("No matching Voxy jar was found for bundled AeroSMP patches. Expected one of: " + String.join("; ", missing));
    }

    private static List<BinaryPatch> loadPatches() throws IOException {
        InputStream indexStream = AeroSmpInitTransformer.class.getClassLoader().getResourceAsStream(PATCH_INDEX);
        if (indexStream == null) {
            return List.of();
        }

        List<BinaryPatch> patches = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String resourceName = line.strip();
                if (resourceName.isEmpty() || resourceName.startsWith("#")) {
                    continue;
                }

                String resourcePath = PATCH_DIR + resourceName;
                try (InputStream patchStream = AeroSmpInitTransformer.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (patchStream == null) {
                        throw new IOException("Patch listed in index is missing: " + resourcePath);
                    }
                    BinaryPatch patch = BinaryPatch.read(patchStream);
                    if (patch.isNoop()) {
                        throw new IOException("Bundled patch artifact is a no-op: " + resourcePath + ". Regenerate it with distinct source and target Voxy jars.");
                    }
                    LOGGER.info("Loaded AeroSMP patch artifact: {}", patch.describe());
                    patches.add(patch);
                }
            }
        }
        return patches;
    }

    private static PatchCandidate findCandidate(BinaryPatch patch) throws IOException {
        Set<Path> paths = collectCandidatePaths(patch);
        List<PatchCandidate> sourceMatches = new ArrayList<>();
        List<PatchCandidate> targetMatches = new ArrayList<>();

        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                continue;
            }

            long size = Files.size(path);
            if (size != patch.sourceSize() && size != patch.targetSize()) {
                continue;
            }

            byte[] sha256 = BinaryPatch.sha256(path);
            if (patch.matchesTarget(size, sha256)) {
                targetMatches.add(new PatchCandidate(path, path, CandidateState.TARGET));
            } else if (patch.matchesSource(size, sha256)) {
                sourceMatches.add(new PatchCandidate(path, installPathFor(path), CandidateState.SOURCE));
            }
        }

        if (targetMatches.size() > 1) {
            throw new IOException("Multiple Voxy jars already match target checksum: " + targetMatches.stream().map(PatchCandidate::path).toList());
        }
        if (sourceMatches.size() > 1) {
            throw new IOException("Multiple Voxy jars match source checksum: " + sourceMatches.stream().map(PatchCandidate::path).toList());
        }
        if (!targetMatches.isEmpty()) {
            return targetMatches.getFirst();
        }
        if (!sourceMatches.isEmpty()) {
            return sourceMatches.getFirst();
        }
        return null;
    }

    private static Set<Path> collectCandidatePaths(BinaryPatch patch) {
        Set<Path> paths = new LinkedHashSet<>();

        String override = System.getProperty(OVERRIDE_JAR_PROPERTY);
        if (override != null && !override.isBlank()) {
            paths.add(Path.of(override));
        }

        String installOverride = System.getProperty(INSTALL_JAR_PROPERTY);
        if (installOverride != null && !installOverride.isBlank()) {
            paths.add(Path.of(installOverride));
        }

        ModList modList = ModList.get();
        if (modList != null) {
            IModFileInfo modFileInfo = modList.getModFileById(patch.targetModId());
            if (modFileInfo != null && modFileInfo.getFile() != null) {
                paths.add(modFileInfo.getFile().getFilePath());
            }
        }

        Path gamePath = FMLLoader.getGamePath();
        if (gamePath != null) {
            Path modsDir = gamePath.resolve("mods");
            if (Files.isDirectory(modsDir)) {
                try (var stream = Files.list(modsDir)) {
                    stream.filter(AeroSmpInitTransformer::isCandidateFile).forEach(paths::add);
                } catch (IOException e) {
                    LOGGER.warn("Could not scan mods directory {}", modsDir, e);
                }
            }
        }

        return paths;
    }

    private static boolean isCandidateFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".jar") || fileName.endsWith(".jar" + SOURCE_STAGING_SUFFIX);
    }

    private static Path installPathFor(Path sourcePath) {
        String installOverride = System.getProperty(INSTALL_JAR_PROPERTY);
        if (installOverride != null && !installOverride.isBlank()) {
            return Path.of(installOverride);
        }

        String fileName = sourcePath.getFileName().toString();
        if (fileName.endsWith(SOURCE_STAGING_SUFFIX)) {
            Path parent = Objects.requireNonNullElse(sourcePath.getParent(), Path.of("."));
            return parent.resolve(fileName.substring(0, fileName.length() - SOURCE_STAGING_SUFFIX.length()));
        }

        return sourcePath;
    }

    private static PatchInstallResult installPatch(BinaryPatch patch, Path sourcePath, Path installPath) throws IOException {
        Path installParent = Objects.requireNonNullElse(installPath.getParent(), Path.of("."));
        boolean inPlace = sourcePath.equals(installPath);

        if (inPlace) {
            Path backup = installParent.resolve(sourcePath.getFileName() + ".aerosmp-original-" + patch.sourceSha256Hex().substring(0, 12) + ".bak");
            if (!Files.exists(backup)) {
                Files.copy(sourcePath, backup, StandardCopyOption.COPY_ATTRIBUTES);
                LOGGER.info("Backed up original Voxy jar to {}", backup);
            }
        } else {
            if (Files.exists(installPath)) {
                throw new IOException("Refusing to overwrite existing Voxy install target " + installPath + " from staged source " + sourcePath);
            }
            LOGGER.info("Installing patched Voxy jar from staged source {} to {}", sourcePath, installPath);
        }

        Path temp = Files.createTempFile(installParent, installPath.getFileName() + ".aerosmp-", ".tmp");
        patch.apply(sourcePath, temp);

        try {
            PatchTool.moveReplacing(temp, installPath);
            byte[] installedHash = BinaryPatch.sha256(installPath);
            if (!patch.matchesTarget(Files.size(installPath), installedHash)) {
                throw new IOException("Installed Voxy jar did not match target checksum after replacement");
            }
            LOGGER.info("Patched Voxy jar at {}", installPath);
            return new PatchInstallResult(InstallState.REPLACED, installPath);
        } catch (IOException replaceFailure) {
            Path staged = installParent.resolve(installPath.getFileName() + ".aerosmp-patched");
            Files.move(temp, staged, StandardCopyOption.REPLACE_EXISTING);
            scheduleReplacement(staged, installPath, replaceFailure);
            return new PatchInstallResult(InstallState.STAGED, installPath);
        }
    }

    private static void scheduleReplacement(Path staged, Path target, IOException replaceFailure) throws IOException {
        Path selfJar = selfJarPath();
        if (selfJar == null || !Files.isRegularFile(selfJar)) {
            throw new IOException("Could not replace loaded Voxy jar and cannot schedule external replacement from " + selfJar, replaceFailure);
        }

        Path javaBin = javaBinary();
        Path log = target.resolveSibling(target.getFileName() + ".aerosmp-replacer.log");
        List<String> command = List.of(
                javaBin.toString(),
                "-cp",
                selfJar.toString(),
                PatchTool.class.getName(),
                "replace-after-exit",
                Long.toString(ProcessHandle.current().pid()),
                staged.toString(),
                target.toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        processBuilder.start();
        LOGGER.warn("Voxy jar was locked; staged patched jar at {} and scheduled replacement after exit. Original failure: {}", staged, replaceFailure.toString());
    }

    private static Path selfJarPath() {
        try {
            return Path.of(AeroSmpInitTransformer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | NullPointerException e) {
            LOGGER.warn("Could not resolve transformer jar path", e);
            return null;
        }
    }

    private static Path javaBinary() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "javaw.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private record PatchCandidate(Path path, Path installPath, CandidateState state) {
    }

    private record PatchInstallResult(InstallState state, Path installedPath) {
    }

    private enum CandidateState {
        SOURCE,
        TARGET
    }

    private enum InstallState {
        REPLACED,
        STAGED
    }

    private static final class RestartRequiredException extends RuntimeException {
        private RestartRequiredException(String message) {
            super(message);
        }
    }
}
