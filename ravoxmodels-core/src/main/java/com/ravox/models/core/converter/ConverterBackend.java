package com.ravox.models.core.converter;

public interface ConverterBackend {
    ConversionResult convert(ConversionRequest request);
    String name();
}
