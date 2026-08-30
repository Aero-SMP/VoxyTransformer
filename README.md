# Voxy Transformer

Voxy Transformer is a narrowly scoped NeoForge 1.21.1 bootstrap mod for AeroSMP. During NeoForge's mod-discovery phase, it reconstructs a pinned NeoForge build of Voxy from a pinned source JAR and registers the generated JAR in the same launch.

It is not a general-purpose mod transformer: the source filename, output filename, and embedded patch are currently hard-coded for one Voxy source/target pair.

## What happens at launch

`VoxyCandidateLocator` is loaded as an early `IModFileCandidateLocator` service. It then:

1. Loads the bundled `voxy-0.2.15-beta-neoforge.aitp` binary patch and rejects it if it is missing or is a no-op.
2. Checks for an already generated JAR at:

   ```text
   .aerosmp/voxy/voxy-0.2.15-beta+1.21.1-neoforge.jar
   ```

3. Reuses that JAR when its size and SHA-256 match the target recorded in the patch. In this case, the source file is not required.
4. Otherwise, reads the source from the exact path:

   ```text
   mods/voxy-0.2.9-alpha-1.21.11.jar.source
   ```

   The `.source` suffix keeps NeoForge from discovering it as a normal mod JAR.
5. Verifies the source size and SHA-256, applies the patch to a temporary file, verifies the generated target, and moves it into the cache path above. The source file is left unchanged.
6. Adds the generated JAR directly to NeoForge's discovery pipeline, allowing Voxy to load immediately without a second launch.

When a rebuild is needed, a missing or modified source causes startup to fail. An invalid patch, a target verification failure, or NeoForge rejecting the generated mod candidate also aborts startup instead of loading an unverified JAR.

## Pinned artifacts

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| Source: `voxy-0.2.9-alpha-1.21.11.jar.source` | 12,612,849 bytes | `7a640f888c11f33d765dd6b7e6666538d04c1a5ea0e2160bd02acfa5ad6f6ef4` |
| Target: `voxy-0.2.15-beta+1.21.1-neoforge.jar` | 89,370,532 bytes | `e084e9bd29169e4d1ec20b88dacddbb07823bc2a527c767a9098b2a6bb8038e0` |

The repository's canonical target JAR is `used_jar/voxy-0.2.15-beta+1.21.1-neoforge.jar`. The source download URL, size, SHA-1, SHA-256, and SHA-512 used by the build are pinned in `gradle.properties`.

## Installation

Use Minecraft 1.21.1, Java 21, and NeoForge 21.1.228 or newer. The transformer is built against NeoForge 21.1.229, while the generated Voxy JAR declares 21.1.228 as its minimum.

For a clean installation:

1. Put the built Voxy Transformer JAR in the instance's `mods/` directory.
2. Put the pinned source download in the same directory under the exact name `voxy-0.2.9-alpha-1.21.11.jar.source`.
3. Do not install another Voxy JAR alongside it.
4. Start the game. The generated Voxy JAR remains under `.aerosmp/voxy/` and is reused on later launches after checksum verification.

## Building

The project uses the Gradle wrapper and a Java 21 toolchain:

```bash
./gradlew build
```

`build` downloads the pinned source JAR when `-PsourceJar` is not supplied, verifies it, regenerates the embedded patch against the target in `used_jar/`, validates that the patch is not a no-op, runs the patch format self-test, and builds the transformer JAR under `build/libs/`.

Useful patching tasks:

```bash
# Regenerate the bundled patch from the pinned source and target
./gradlew generateVoxyPatch

# Print the bundled patch's source, target, size, and hunk metadata
./gradlew inspectVoxyPatch

# Validate the bundled patch and run the in-process patch tests
./gradlew validateBundledPatch selfTestPatchTool
```

Local source and target JARs can be selected without changing `gradle.properties`:

```bash
./gradlew generateVoxyPatch \
  -PsourceJar=/path/to/source.jar \
  -PtargetJar=/path/to/target.jar
```

`-PpatchOut=/path/to/output.aitp` changes the generation output, and `./gradlew inspectVoxyPatch -Ppatch=/path/to/patch.aitp` inspects a different patch. Patch generation and validation reject identical source and target files unless `-PallowEmptyPatch=true` is explicitly supplied for testing.

## Implementation notes

- `VoxyTransformer` is only the JavaFML mod-list entry point; `VoxyCandidateLocator` performs the bootstrap work.
- The locator has no configuration file or user interface and handles only the filenames compiled into it.
- `BinaryPatch` version 2 supports XOR hunks for same-offset changes and replacement hunks for inserted or removed bytes. Patch application verifies both endpoints with SHA-256.
- Target replacement is atomic when the filesystem supports it, with a normal replace move as a fallback.
