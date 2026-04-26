package com.ravox.models.core.importer;

public record ImportLimits(int maxTriangles, int maxBones, int maxTextureSize) {
    public static ImportLimits fromConfig(int maxTriangles, int maxBones, int maxTextureSize) {
        return new ImportLimits(
                Math.max(1, maxTriangles),
                Math.max(1, maxBones),
                Math.max(16, maxTextureSize)
        );
    }
}
