package com.ravox.models.core.converter;

import com.ravox.models.core.model.ModelFormat;

import java.nio.file.Path;

public record ConversionRequest(
        String modelId,
        ModelFormat format,
        Path sourceFile,
        Path modelDirectory,
        Path runtimeDirectory,
        Path pluginDataDirectory
) {
}
