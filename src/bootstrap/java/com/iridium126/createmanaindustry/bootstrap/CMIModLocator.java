package com.iridium126.createmanaindustry.bootstrap;

import cpw.mods.jarhandling.JarContents;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import net.neoforged.neoforgespi.locating.ModFileLoadingException;

/** Exposes the embedded mod from the distribution's separate service layer. */
public final class CMIModLocator implements IModFileCandidateLocator {
    private static final String MOD_RESOURCE = "/META-INF/jars/createmanaindustry.jar";

    @Override
    @SuppressWarnings("resource") // FML keeps the nested filesystem open for the mod's lifetime.
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        var resource = CMIModLocator.class.getResource(MOD_RESOURCE);
        // Gradle/IDE runs use a bootstrap-only jar and load main from its source set.
        if (resource == null) {
            return;
        }
        try {
            var nestedPath = Path.of(resource.toURI());
            var uri = new URI("jij:" + nestedPath.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
            var fileSystem = FileSystems.newFileSystem(uri, Map.of("packagePath", nestedPath));
            var loaded = pipeline.addJarContent(JarContents.of(fileSystem.getPath("/")),
                    ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
            if (loaded.isEmpty()) {
                throw new IllegalStateException("Embedded Create Mana Industry mod was not discovered");
            }
        } catch (Exception e) {
            var failure = new ModFileLoadingException("Failed to load embedded Create Mana Industry mod");
            failure.initCause(e);
            throw failure;
        }
    }
}
