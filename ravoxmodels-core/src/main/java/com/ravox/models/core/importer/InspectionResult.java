package com.ravox.models.core.importer;

import com.ravox.models.core.model.ModelFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InspectionResult {
    private final boolean valid;
    private final String message;
    private final ModelFormat format;
    private final int estimatedTriangles;
    private final int skinBones;
    private final int maxTextureSize;
    private final int animationCount;
    private final List<String> animationNames;
    private final byte[] previewTexturePng;
    private final List<String> warnings;

    private InspectionResult(
            boolean valid,
            String message,
            ModelFormat format,
            int estimatedTriangles,
            int skinBones,
            int maxTextureSize,
            int animationCount,
            List<String> animationNames,
            byte[] previewTexturePng,
            List<String> warnings
    ) {
        this.valid = valid;
        this.message = message;
        this.format = format;
        this.estimatedTriangles = estimatedTriangles;
        this.skinBones = skinBones;
        this.maxTextureSize = maxTextureSize;
        this.animationCount = animationCount;
        this.animationNames = Collections.unmodifiableList(new ArrayList<>(animationNames));
        this.previewTexturePng = previewTexturePng == null ? null : previewTexturePng.clone();
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public static InspectionResult success(
            ModelFormat format,
            int estimatedTriangles,
            int skinBones,
            int maxTextureSize,
            int animationCount,
            List<String> animationNames,
            byte[] previewTexturePng,
            List<String> warnings
    ) {
        return new InspectionResult(true, "ok", format, estimatedTriangles, skinBones, maxTextureSize, animationCount, animationNames, previewTexturePng, warnings);
    }

    public static InspectionResult failure(ModelFormat format, String message) {
        return new InspectionResult(false, message, format, 0, 0, 0, 0, List.of(), null, List.of());
    }

    public boolean isValid() {
        return valid;
    }

    public String getMessage() {
        return message;
    }

    public ModelFormat getFormat() {
        return format;
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

    public List<String> getAnimationNames() {
        return animationNames;
    }

    public byte[] getPreviewTexturePng() {
        return previewTexturePng == null ? null : previewTexturePng.clone();
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
