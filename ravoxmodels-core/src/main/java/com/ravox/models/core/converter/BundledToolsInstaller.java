package com.ravox.models.core.converter;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
            Files.createDirectories(target.getParent());
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in == null) {
                    plugin.getLogger().warning("Bundled tool missing in jar: " + resourcePath);
                    return;
                }
                byte[] bundled = in.readAllBytes();
                if (Files.exists(target) && !overwrite && Arrays.equals(Files.readAllBytes(target), bundled)) {
                    return;
                }
                Files.write(target, bundled);
            }
            target.toFile().setReadable(true, false);
            target.toFile().setWritable(true, true);
            target.toFile().setExecutable(true, false);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not install bundled tool " + resourcePath + ": " + ex.getMessage());
        }
    }
}
