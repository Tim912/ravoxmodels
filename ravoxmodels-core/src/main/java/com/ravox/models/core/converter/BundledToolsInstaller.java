package com.ravox.models.core.converter;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class BundledToolsInstaller {
    private final JavaPlugin plugin;

    public BundledToolsInstaller(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void install(boolean overwrite) {
        installOne("tools/converter_backend.py", overwrite);
        installOne("tools/converter_blender_bridge.py", overwrite);
    }

    private void installOne(String resourcePath, boolean overwrite) {
        Path target = plugin.getDataFolder().toPath().resolve(resourcePath.replace('/', java.io.File.separatorChar)).normalize();
        try {
            if (Files.exists(target) && !overwrite) {
                return;
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in == null) {
                    plugin.getLogger().warning("Bundled tool missing in jar: " + resourcePath);
                    return;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            target.toFile().setReadable(true, false);
            target.toFile().setWritable(true, true);
            target.toFile().setExecutable(true, false);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not install bundled tool " + resourcePath + ": " + ex.getMessage());
        }
    }
}
