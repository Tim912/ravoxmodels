package com.ravox.models.core.converter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConversionResult {
    private final boolean success;
    private final String backendName;
    private final String message;
    private final List<String> artifacts;
    private final List<String> warnings;
    private final List<String> animationNames;

    private ConversionResult(
            boolean success,
            String backendName,
            String message,
            List<String> artifacts,
            List<String> warnings,
            List<String> animationNames
    ) {
        this.success = success;
        this.backendName = backendName;
        this.message = message;
        this.artifacts = Collections.unmodifiableList(new ArrayList<>(artifacts));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.animationNames = Collections.unmodifiableList(new ArrayList<>(animationNames));
    }

    public static ConversionResult success(String backendName, String message, List<String> artifacts, List<String> warnings, List<String> animationNames) {
        return new ConversionResult(true, backendName, message, artifacts, warnings, animationNames);
    }

    public static ConversionResult failure(String backendName, String message) {
        return new ConversionResult(false, backendName, message, List.of(), List.of(), List.of());
    }

    public boolean isSuccess() {
        return success;
    }

    public String getBackendName() {
        return backendName;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getArtifacts() {
        return artifacts;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getAnimationNames() {
        return animationNames;
    }
}
