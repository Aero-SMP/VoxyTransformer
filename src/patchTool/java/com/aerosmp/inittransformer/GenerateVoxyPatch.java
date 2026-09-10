package com.aerosmp.inittransformer;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** Build-only generator; not shipped in the transformer JAR. */
public final class GenerateVoxyPatch {
    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected source JAR, target JAR, source filename, source SHA-256, output");
        }
        byte[] source = Files.readAllBytes(Path.of(args[0]));
        byte[] sourceHash = XorPatch.sha256(source);
        if (!HexFormat.of().formatHex(sourceHash).equalsIgnoreCase(args[3])) {
            throw new IOException("Source JAR does not match the pinned SHA-256");
        }
        Path targetPath = Path.of(args[1]);
        byte[] target = Files.readAllBytes(targetPath);
        byte[] targetHash = XorPatch.sha256(target);
        byte[] xor = target.clone();
        for (int i = 0; i < Math.min(source.length, xor.length); i++) {
            xor[i] ^= source[i];
        }

        Path output = Path.of(args[4]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            data.writeInt(XorPatch.MAGIC);
            data.writeInt(XorPatch.VERSION);
            data.writeUTF(args[2]);
            data.writeUTF(targetPath.getFileName().toString());
            data.writeInt(source.length);
            data.writeInt(target.length);
            data.write(sourceHash);
            data.write(targetHash);
            data.write(xor);
        }
        System.out.println("Generated XOR for " + targetPath.getFileName()
                + " SHA-256 " + HexFormat.of().formatHex(targetHash));
    }
}
