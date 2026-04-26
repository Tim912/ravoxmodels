package com.ravox.models.core.converter;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConverterPipeline {
    private final boolean strict;
    private final ConverterBackend commandBackend;
    private final ConverterBackend noopBackend = new NoopConverterBackend();

    public ConverterPipeline(JavaPlugin plugin, FileConfiguration config) {
        this.strict = config.getBoolean("converter.strict", false);
        this.commandBackend = new CommandConverterBackend(plugin, config);
    }

    public ConversionResult convert(ConversionRequest request) {
        ConversionResult command = commandBackend.convert(request);
        if (command.isSuccess()) {
            return command;
        }
        if (strict) {
            return command;
        }
        ConversionResult fallback = noopBackend.convert(request);
        return ConversionResult.success(
                fallback.getBackendName(),
                fallback.getMessage(),
                fallback.getArtifacts(),
                combineWarnings(command.getMessage(), fallback.getWarnings()),
                fallback.getAnimationNames()
        );
    }

    private static java.util.List<String> combineWarnings(String primaryFailure, java.util.List<String> fallbackWarnings) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        warnings.add("Converter fallback used because primary backend failed: " + primaryFailure);
        warnings.addAll(fallbackWarnings);
        return warnings;
    }
}
