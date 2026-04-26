package com.ravox.models.core.importer;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class ImportService {
    private final File importDirectory;
    private final Set<String> acceptedExtensions;

    public ImportService(File dataFolder, FileConfiguration config) {
        String configuredPath = config.getString("import.directory", "plugins/RavoxModels/import");
        this.importDirectory = resolvePath(dataFolder, configuredPath);
        this.acceptedExtensions = new HashSet<>();
        for (String ext : config.getStringList("import.accepted_extensions")) {
            acceptedExtensions.add(ext.toLowerCase(Locale.ROOT));
        }
        if (acceptedExtensions.isEmpty()) {
            acceptedExtensions.add("glb");
            acceptedExtensions.add("fbx");
        }
        if (!importDirectory.exists()) {
            importDirectory.mkdirs();
        }
    }

    public File getImportDirectory() {
        return importDirectory;
    }

    public boolean queue(String filename) {
        File file = new File(importDirectory, filename);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        String ext = extension(filename);
        return acceptedExtensions.contains(ext);
    }

    private static String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private static File resolvePath(File dataFolder, String configuredPath) {
        File configured = new File(configuredPath);
        if (configured.isAbsolute()) {
            return configured;
        }
        return new File(dataFolder.getParentFile(), configuredPath);
    }
}
