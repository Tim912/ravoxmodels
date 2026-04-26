package com.ravox.models.core.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ravox.models.core.model.ModelFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class GlbInspector {
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;

    InspectionResult inspect(Path file, ImportLimits limits) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            return InspectionResult.failure(ModelFormat.GLB, "Could not read file: " + ex.getMessage());
        }
        if (bytes.length < 20) {
            return InspectionResult.failure(ModelFormat.GLB, "Invalid GLB: file too small");
        }

        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte g = header.get(0);
        byte l = header.get(1);
        byte t = header.get(2);
        byte f = header.get(3);
        if (g != 'g' || l != 'l' || t != 'T' || f != 'F') {
            return InspectionResult.failure(ModelFormat.GLB, "Invalid GLB magic");
        }
        int version = header.getInt(4);
        if (version != 2) {
            return InspectionResult.failure(ModelFormat.GLB, "Unsupported GLB version: " + version);
        }
        int declaredLength = header.getInt(8);
        if (declaredLength != bytes.length) {
            return InspectionResult.failure(ModelFormat.GLB, "GLB declared length mismatch");
        }

        byte[] jsonChunk = null;
        byte[] binChunk = null;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int chunkLength = readIntLE(bytes, offset);
            int chunkType = readIntLE(bytes, offset + 4);
            offset += 8;
            if (chunkLength < 0 || offset + chunkLength > bytes.length) {
                return InspectionResult.failure(ModelFormat.GLB, "Invalid GLB chunk layout");
            }
            byte[] chunkData = slice(bytes, offset, chunkLength);
            offset += chunkLength;

            if (chunkType == JSON_CHUNK_TYPE) {
                jsonChunk = chunkData;
            } else if (chunkType == BIN_CHUNK_TYPE && binChunk == null) {
                binChunk = chunkData;
            }
        }
        if (jsonChunk == null) {
            return InspectionResult.failure(ModelFormat.GLB, "GLB is missing JSON chunk");
        }

        String json = new String(jsonChunk, StandardCharsets.UTF_8).trim();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException ex) {
            return InspectionResult.failure(ModelFormat.GLB, "Invalid GLB JSON: " + ex.getMessage());
        }

        JsonArray accessors = array(root, "accessors");
        int[] accessorCounts = new int[accessors.size()];
        for (int i = 0; i < accessors.size(); i++) {
            JsonObject accessor = object(accessors.get(i));
            accessorCounts[i] = intOr(accessor, "count", 0);
        }

        int triangles = 0;
        JsonArray meshes = array(root, "meshes");
        for (JsonElement meshElement : meshes) {
            JsonObject mesh = object(meshElement);
            JsonArray primitives = array(mesh, "primitives");
            for (JsonElement primitiveElement : primitives) {
                JsonObject primitive = object(primitiveElement);
                int mode = intOr(primitive, "mode", 4);
                if (mode != 4) {
                    continue;
                }
                if (primitive.has("indices")) {
                    int accessorIndex = primitive.get("indices").getAsInt();
                    if (accessorIndex >= 0 && accessorIndex < accessorCounts.length) {
                        triangles += accessorCounts[accessorIndex] / 3;
                    }
                    continue;
                }
                JsonObject attrs = object(primitive.get("attributes"));
                if (attrs.has("POSITION")) {
                    int positionAccessor = attrs.get("POSITION").getAsInt();
                    if (positionAccessor >= 0 && positionAccessor < accessorCounts.length) {
                        triangles += accessorCounts[positionAccessor] / 3;
                    }
                }
            }
        }

        int maxBones = 0;
        JsonArray skins = array(root, "skins");
        for (JsonElement skinElement : skins) {
            JsonObject skin = object(skinElement);
            JsonArray joints = array(skin, "joints");
            maxBones = Math.max(maxBones, joints.size());
        }

        List<String> animationNames = new ArrayList<>();
        JsonArray animations = array(root, "animations");
        for (int i = 0; i < animations.size(); i++) {
            JsonObject animation = object(animations.get(i));
            String name = stringOr(animation, "name", "");
            if (name.isBlank()) {
                name = "animation_" + (i + 1);
            }
            animationNames.add(name);
        }

        JsonArray bufferViews = array(root, "bufferViews");
        JsonArray images = array(root, "images");
        int maxTextureSize = 0;
        byte[] previewPng = null;
        List<String> warnings = new ArrayList<>();

        for (JsonElement imageElement : images) {
            JsonObject image = object(imageElement);
            byte[] imageData = extractImageBytes(file, image, bufferViews, binChunk);
            if (imageData == null || imageData.length == 0) {
                continue;
            }
            BufferedImage decoded = decodeImage(imageData);
            if (decoded == null) {
                warnings.add("Could not decode one embedded texture.");
                continue;
            }
            maxTextureSize = Math.max(maxTextureSize, Math.max(decoded.getWidth(), decoded.getHeight()));
            if (previewPng == null) {
                previewPng = asPng(decoded);
            }
        }

        if (images.isEmpty()) {
            warnings.add("Model has no embedded image; generated texture placeholder will be used.");
        }
        if (animations.isEmpty()) {
            warnings.add("Model has no animation tracks.");
        }

        if (triangles > limits.maxTriangles()) {
            return InspectionResult.failure(ModelFormat.GLB, "Triangle limit exceeded: " + triangles + " > " + limits.maxTriangles());
        }
        if (maxBones > limits.maxBones()) {
            return InspectionResult.failure(ModelFormat.GLB, "Bone limit exceeded: " + maxBones + " > " + limits.maxBones());
        }
        if (maxTextureSize > limits.maxTextureSize()) {
            return InspectionResult.failure(ModelFormat.GLB, "Texture limit exceeded: " + maxTextureSize + " > " + limits.maxTextureSize());
        }

        return InspectionResult.success(
                ModelFormat.GLB,
                triangles,
                maxBones,
                maxTextureSize,
                animationNames.size(),
                animationNames,
                previewPng,
                warnings
        );
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return new JsonObject();
        }
        return element.getAsJsonObject();
    }

    private static int intOr(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF))
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static byte[] slice(byte[] source, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, offset, out, 0, length);
        return out;
    }

    private static byte[] extractImageBytes(Path glbPath, JsonObject image, JsonArray bufferViews, byte[] binChunk) {
        if (image.has("uri")) {
            String uri = stringOr(image, "uri", "");
            if (uri.startsWith("data:")) {
                int comma = uri.indexOf(',');
                if (comma > 0 && comma + 1 < uri.length()) {
                    String base64 = uri.substring(comma + 1);
                    try {
                        return Base64.getDecoder().decode(base64);
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                }
                return null;
            }
            Path external = glbPath.getParent().resolve(uri).normalize();
            try {
                if (Files.exists(external)) {
                    return Files.readAllBytes(external);
                }
            } catch (IOException ignored) {
                return null;
            }
        }

        if (image.has("bufferView")) {
            int bufferViewIndex = intOr(image, "bufferView", -1);
            if (bufferViewIndex < 0 || bufferViewIndex >= bufferViews.size() || binChunk == null) {
                return null;
            }
            JsonObject view = object(bufferViews.get(bufferViewIndex));
            int offset = intOr(view, "byteOffset", 0);
            int length = intOr(view, "byteLength", 0);
            if (offset < 0 || length <= 0 || offset + length > binChunk.length) {
                return null;
            }
            return slice(binChunk, offset, length);
        }
        return null;
    }

    private static BufferedImage decodeImage(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (IOException ex) {
            return null;
        }
    }

    private static byte[] asPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }
}
