# AeroSMP Init Transformer

NeoForge 1.21.1 bootstrap mod that applies an embedded binary patch artifact to an installed Voxy jar on first launch.

The transformer never patches an unknown jar. It only acts when the installed Voxy jar matches the source checksum recorded in the patch artifact. Same-size changed regions are stored as XOR deltas; true insert/delete regions are stored as replacement hunks. After patching it verifies the target checksum and then forces a restart by failing mod loading with a clear message. Later launches detect the target checksum and do nothing.

This project packages the transformer and patch artifact only. The matching source Voxy jar is fetched from Modrinth during patch generation.

## Generate the Voxy Patch

First publish the local NeoForge Voxy fork to Maven local:

```bash
cd ../voxy-neoforge
./gradlew publishToMavenLocal
```

Then generate the patch from this project:

```bash
./gradlew generateVoxyPatch
```

The default source input is downloaded and verified from Modrinth:

```text
https://cdn.modrinth.com/data/fxxUqruK/versions/1rOxxZT5/voxy-0.2.9-alpha-1.21.11.jar
size   12612849
sha1   eee7eeee8ae1c5be118d0cb57683baf2533a67d2
sha256 7a640f888c11f33d765dd6b7e6666538d04c1a5ea0e2160bd02acfa5ad6f6ef4
sha512 6d85e327b5e32b8d8a33c561bbeef92f66e560c0fa52778207a80bd9834e4ce5c398c5d75b83224ba66a599fe9b1308c36c875f78e04d896399f1fa74a2cc21f
```

The default target input is resolved from Maven local:

```text
me.cortex:voxy:0.2.9-alpha
```

Override inputs when needed:

```bash
./gradlew generateVoxyPatch \
  -PsourceJar=/path/to/original/voxy.jar \
  -PtargetJar=/path/to/aerosmp/voxy.jar
```

This writes:

- `src/main/resources/aerosmp/inittransformer/patches/voxy-0.2.9-alpha.aitp`
- `src/main/resources/aerosmp/inittransformer/patches/index.txt`

Generation refuses identical source and target jars by default. For scaffold testing only, use:

```bash
./gradlew generateVoxyPatch -PallowEmptyPatch=true
```

## First Launch

Without Sinytra Connector, put the transformer jar and the official Modrinth Voxy jar in `mods/`. On first launch the transformer patches the Voxy jar in place, writes an `.aerosmp-original-...bak` backup beside it, and stops loading so the instance can be restarted.

With Sinytra Connector present, do not place the official Fabric Voxy jar in `mods/` as a normal `.jar`; Connector may resolve it before the transformer can run. Instead, stage it with the `.aerosmp-source` suffix:

```text
mods/voxy-0.2.9-alpha-1.21.11.jar.aerosmp-source
mods/aerosmp_init_transformer-0.1.0.jar
```

The transformer scans that staged source file, writes the patched jar to:

```text
mods/voxy-0.2.9-alpha-1.21.11.jar
```

and leaves the staged source file untouched for checksum verification and future recovery. The staged source file is not a loadable `.jar`, so NeoForge and Connector ignore it during discovery.

## Build and Verify

```bash
cd ../voxy-neoforge
./gradlew publishToMavenLocal
cd ../AeroSMPInitTransformer
./gradlew build
./gradlew inspectVoxyPatch
```

The normal `build` task regenerates and validates that the bundled patch is non-empty. For scaffold testing only:

```bash
./gradlew build -PallowEmptyPatch=true
```
