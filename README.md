# Voxy Transformer 1.1.1

Reconstructs the pinned ASMP Voxy client using XOR and registers it with NeoForge during the same launch. No restart is needed.

## Installation

Use Minecraft 1.21.1, Java 21, and NeoForge 21.1.229 or newer for that Minecraft version. The target Voxy client requires Sodium (its metadata declares 0.6.0 or newer); Iris is optional. Install compatible Minecraft 1.21.1 NeoForge builds separately.

Place these files in the instance's `mods/` directory:

- `voxy_transformer-1.1.1.jar`
- The original pinned Voxy download renamed to `voxy-0.2.9-alpha-1.21.11.jar.source`

On launch, the transformer checks `.aerosmp/voxy/ASMP_voxy-0.2.213-beta+1.21.1-neoforge.jar`. If its size and SHA-256 match, it is reused, even if the source is absent. Otherwise, the transformer verifies the source, reconstructs the target, verifies the result, and replaces the output through a temporary file. The source is left unchanged. Preparation failures abort startup with an error.

The generated JAR is registered directly during mod discovery. Do not install a second Voxy JAR in `mods/`. Previous cached versions are not registered or deleted.

## Pinned binaries

| | Source | Target |
| --- | --- | --- |
| Filename | `voxy-0.2.9-alpha-1.21.11.jar.source` | `ASMP_voxy-0.2.213-beta+1.21.1-neoforge.jar` |
| Bytes | 12,612,849 | 3,747,119 |
| SHA-256 | `7a640f888c11f33d765dd6b7e6666538d04c1a5ea0e2160bd02acfa5ad6f6ef4` | `712d10c8a8de0f6379465f1f0dcf3ec16f69270b149690a07af91823b66bf7d1` |

The target in `used_jar/` is the non-debug client build `0f6d75c8` from `ASMP_Voxy/build/release-validation-213/`. The required source is the original Modrinth artifact; renaming another version will not work.

## Build

Supply the pinned source JAR locally; the build does not download it:

```bash
./gradlew build -PsourceJar=/absolute/path/to/voxy-0.2.9-alpha-1.21.11.jar
```

The result is `build/libs/voxy_transformer-1.1.1.jar`. Source filename, source SHA-256, and target path are configured in `gradle.properties`; runtime filenames and checksums are embedded in the generated XOR resource.

`generateVoxyPatch` runs a build-only Java generator without Minecraft dependencies and produces `build/generated/voxyPatch/aerosmp/voxy.xor` before resource processing. This resource is packaged automatically and is not checked into the repository. The format contains filenames, sizes, SHA-256 hashes, and one target-length XOR stream. Reconstruction truncates to the target length or treats missing source bytes as zero when growing.

`build` also runs `verifyVoxyPatch`: it reconstructs the exact target from the packaged resource and exercises cache reuse, repair, invalid inputs, and file replacement. These checks do not launch Minecraft.
