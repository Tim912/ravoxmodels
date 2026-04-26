package com.ravox.models.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ModelDefinition {
    private final String id;
    private final ModelFormat format;
    private final String sourceFilename;
    private final String sourceSha1;
    private final long importedAtEpochMillis;
    private final int estimatedTriangles;
    private final int skinBones;
    private final int maxTextureSize;
    private final int animationCount;
    private final List<String> animationKeys;
    private final int customModelData;
    private final String materialKey;
    private final String modelDirectory;
    private final String manifestPath;
    private final String previewTexturePath;
    private final boolean converterApplied;
    private final String converterName;
    private final List<String> runtimeArtifacts;
    private final List<String> warnings;

    public ModelDefinition(
            String id,
            ModelFormat format,
            String sourceFilename,
            String sourceSha1,
            long importedAtEpochMillis,
            int estimatedTriangles,
            int skinBones,
            int maxTextureSize,
            int animationCount,
            List<String> animationKeys,
            int customModelData,
            String materialKey,
            String modelDirectory,
            String manifestPath,
            String previewTexturePath,
            boolean converterApplied,
            String converterName,
            List<String> runtimeArtifacts,
            List<String> warnings
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.format = Objects.requireNonNull(format, "format");
        this.sourceFilename = Objects.requireNonNull(sourceFilename, "sourceFilename");
        this.sourceSha1 = Objects.requireNonNull(sourceSha1, "sourceSha1");
        this.importedAtEpochMillis = importedAtEpochMillis;
        this.estimatedTriangles = estimatedTriangles;
        this.skinBones = skinBones;
        this.maxTextureSize = maxTextureSize;
        this.animationCount = animationCount;
        this.animationKeys = Collections.unmodifiableList(new ArrayList<>(animationKeys));
        this.customModelData = customModelData;
        this.materialKey = Objects.requireNonNull(materialKey, "materialKey");
        this.modelDirectory = Objects.requireNonNull(modelDirectory, "modelDirectory");
        this.manifestPath = Objects.requireNonNull(manifestPath, "manifestPath");
        this.previewTexturePath = previewTexturePath;
        this.converterApplied = converterApplied;
        this.converterName = converterName == null ? "" : converterName;
        this.runtimeArtifacts = Collections.unmodifiableList(new ArrayList<>(runtimeArtifacts));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public String getId() {
        return id;
    }

    public ModelFormat getFormat() {
        return format;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public String getSourceSha1() {
        return sourceSha1;
    }

    public long getImportedAtEpochMillis() {
        return importedAtEpochMillis;
    }

    public int getEstimatedTriangles() {
        return estimatedTriangles;
    }

    public int getSkinBones() {
        return skinBones;
    }

    public int getMaxTextureSize() {
        return maxTextureSize;
    }

    public int getAnimationCount() {
        return animationCount;
    }

    public List<String> getAnimationKeys() {
        return animationKeys;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public String getMaterialKey() {
        return materialKey;
    }

    public String getModelDirectory() {
        return modelDirectory;
    }

    public String getManifestPath() {
        return manifestPath;
    }

    public String getPreviewTexturePath() {
        return previewTexturePath;
    }

    public boolean isConverterApplied() {
        return converterApplied;
    }

    public String getConverterName() {
        return converterName;
    }

    public List<String> getRuntimeArtifacts() {
        return runtimeArtifacts;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
