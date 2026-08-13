# Voxy_Transformer

NeoForge 1.21.1 bootstrap mod that converts the official Fabric Voxy jar into the AeroSMP NeoForge fork during the same launch.

The Modrinth pack installs the source as:

```text
mods/voxy-0.2.9-alpha-1.21.11.jar.source
```

NeoForge ignores that file because it does not end in `.jar`. An early `IModFileCandidateLocator` verifies the source checksum, applies the embedded binary patch, verifies the exact target checksum, writes the result to:

```text
.aerosmp/voxy/voxy-0.2.15-beta+1.21.1-neoforge.jar
```

The locator registers that result with NeoForge before normal mod discovery, so the generated Voxy fork loads on the first launch without a restart. Later launches reuse the checksum-verified cached result.

The canonical target is:

```text
used_jar/voxy-0.2.15-beta+1.21.1-neoforge.jar
SHA-256 e084e9bd29169e4d1ec20b88dacddbb07823bc2a527c767a9098b2a6bb8038e0
```

Generate and inspect the patch:

```bash
./gradlew generateVoxyPatch
./gradlew inspectVoxyPatch
```

Build:

```bash
./gradlew build
```

The source download remains checksum-pinned in `gradle.properties`. Patch generation refuses an identical source and target unless `-PallowEmptyPatch=true` is explicitly supplied for testing.
