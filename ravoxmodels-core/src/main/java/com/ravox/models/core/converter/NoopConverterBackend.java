package com.ravox.models.core.converter;

import java.util.List;

public final class NoopConverterBackend implements ConverterBackend {
    @Override
    public ConversionResult convert(ConversionRequest request) {
        return ConversionResult.success(
                name(),
                "No converter configured. Metadata/import package created only.",
                List.of(),
                List.of("No converter backend active; runtime uses generated resourcepack placeholders."),
                List.of()
        );
    }

    @Override
    public String name() {
        return "noop";
    }
}
