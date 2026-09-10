package com.aerosmp.inittransformer;

import cpw.mods.modlauncher.api.IEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/** Prepares the pinned Voxy client and registers it during this launch. */
public final class VoxyCandidateLocator implements IModFileCandidateLocator {
    @Override
    public int getPriority() {
        return HIGHEST_SYSTEM_PRIORITY + 100;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            Path gameDirectory = context.environment().getProperty(IEnvironment.Keys.GAMEDIR.get())
                    .orElseThrow(() -> new IOException("NeoForge did not provide the game directory"));
            XorPatch patch = XorPatch.read(getClass().getClassLoader().getResourceAsStream(XorPatch.RESOURCE));
            Path output = patch.prepare(gameDirectory);
            if (pipeline.addPath(output, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR).isEmpty()) {
                throw new IOException("NeoForge did not accept generated Voxy JAR " + output);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare Voxy: " + e.getMessage(), e);
        }
    }
}
