package com.ravox.models.core.importer;

import com.ravox.models.core.model.ModelFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class FbxInspector {
    InspectionResult inspect(Path file, ImportLimits limits) {
        byte[] header = new byte[64];
        int read;
        try {
            byte[] bytes = Files.readAllBytes(file);
            read = Math.min(bytes.length, header.length);
            System.arraycopy(bytes, 0, header, 0, read);
        } catch (IOException ex) {
            return InspectionResult.failure(ModelFormat.FBX, "Could not read file: " + ex.getMessage());
        }
        if (read < 24) {
            return InspectionResult.failure(ModelFormat.FBX, "Invalid FBX: file too small");
        }

        String prefix = new String(header, 0, read, StandardCharsets.US_ASCII);
        List<String> warnings = new ArrayList<>();
        if (prefix.startsWith("Kaydara FBX Binary")) {
            int version = readIntLE(header, 23);
            warnings.add("FBX binary version detected: " + version + ". Mesh/bone counts are validated at converter stage.");
        } else {
            warnings.add("FBX ASCII detected. Mesh/bone counts are validated at converter stage.");
        }
        warnings.add("FBX import requires converter backend for final runtime mesh generation.");

        return InspectionResult.success(
                ModelFormat.FBX,
                0,
                0,
                0,
                0,
                List.of(),
                null,
                warnings
        );
    }

    private static int readIntLE(byte[] bytes, int offset) {
        if (offset + 4 > bytes.length) {
            return 0;
        }
        return ((bytes[offset] & 0xFF))
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
