package com.ravox.models.core.importer;

public final class ImportRecord {
    private final long timestampEpochMillis;
    private final ImportStatus status;
    private final String sourceFile;
    private final String modelId;
    private final String message;

    public ImportRecord(long timestampEpochMillis, ImportStatus status, String sourceFile, String modelId, String message) {
        this.timestampEpochMillis = timestampEpochMillis;
        this.status = status;
        this.sourceFile = sourceFile;
        this.modelId = modelId;
        this.message = message;
    }

    public long getTimestampEpochMillis() {
        return timestampEpochMillis;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public String getModelId() {
        return modelId;
    }

    public String getMessage() {
        return message;
    }
}
