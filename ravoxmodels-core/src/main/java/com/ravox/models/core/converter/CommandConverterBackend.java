package com.ravox.models.core.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ravox.models.core.model.ModelFormat;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class CommandConverterBackend implements ConverterBackend {
    private final JavaPlugin plugin;
    private final boolean enabled;
    private final String glbCommand;
    private final String fbxCommand;
    private final String namespace;
    private final String shell;
    private final int timeoutSeconds;
    private final boolean strictExitCode;
    private final boolean requireReport;

    public CommandConverterBackend(JavaPlugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.enabled = config.getBoolean("converter.command.enabled", false);
        this.namespace = normalizeNamespace(config.getString("resourcepack.model_namespace", "rvxmodels"));
        this.glbCommand = normalizeBundledCommand(config.getString("converter.command.glb", "").trim(), "glb");
        this.fbxCommand = normalizeBundledCommand(config.getString("converter.command.fbx", "").trim(), "fbx");
        this.shell = config.getString("converter.command.shell", "powershell").trim().toLowerCase(Locale.ROOT);
        int minimumTimeout = (glbCommand.contains("converter_backend.py") || fbxCommand.contains("converter_backend.py")) ? 900 : 1;
        this.timeoutSeconds = Math.max(minimumTimeout, config.getInt("converter.command.timeout_seconds", 180));
        this.strictExitCode = config.getBoolean("converter.command.strict_exit_code", true);
        this.requireReport = config.getBoolean("converter.command.require_report", false);
    }

    @Override
    public ConversionResult convert(ConversionRequest request) {
        if (!enabled) {
            return ConversionResult.success(
                    name(),
                    "Command converter disabled.",
                    List.of(),
                    List.of("converter.command.enabled=false"),
                    List.of()
            );
        }
        String template = commandTemplate(request.format());
        if (template.isBlank()) {
            return ConversionResult.failure(name(), "No converter command configured for format: " + request.format());
        }

        Path runtimeDir = request.runtimeDirectory();
        try {
            Files.createDirectories(runtimeDir);
        } catch (IOException ex) {
            return ConversionResult.failure(name(), "Could not create runtime directory: " + ex.getMessage());
        }

        String command = interpolate(template, request);
        ProcessBuilder builder = processBuilderFor(command);
        builder.directory(request.modelDirectory().toFile());
        builder.redirectErrorStream(true);

        String output;
        int exitCode;
        try {
            Process process = builder.start();
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return ConversionResult.failure(name(), "Converter timed out after " + timeoutSeconds + "s");
            }
            exitCode = process.exitValue();
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ConversionResult.failure(name(), "Converter failed to run: " + ex.getMessage());
        }

        Path reportPath = runtimeDir.resolve("conversion-report.json");
        if (Files.exists(reportPath)) {
            return parseReport(reportPath, exitCode, output);
        }
        if (requireReport) {
            return ConversionResult.failure(name(), "Converter report missing: " + reportPath.getFileName());
        }
        if (strictExitCode && exitCode != 0) {
            return ConversionResult.failure(name(), "Converter exit code " + exitCode + ": " + safeShort(output));
        }
        List<String> artifacts = listArtifacts(runtimeDir, request.modelDirectory());
        return ConversionResult.success(
                name(),
                "Converter completed with exit code " + exitCode,
                artifacts,
                output.isBlank() ? List.of() : List.of(safeShort(output)),
                List.of()
        );
    }

    @Override
    public String name() {
        return "command";
    }

    private String commandTemplate(ModelFormat format) {
        return switch (format) {
            case GLB -> glbCommand;
            case FBX -> fbxCommand;
        };
    }

    private String normalizeBundledCommand(String raw, String format) {
        if (raw.contains("converter_backend.py") && !raw.contains("--max-elements")) {
            plugin.getLogger().info("Upgrading legacy bundled converter command for " + format + " at runtime.");
            return defaultBundledCommand();
        }
        return raw;
    }

    private static String defaultBundledCommand() {
        return "py -3 {plugin_dir}/tools/converter_backend.py"
                + " --input {input}"
                + " --output {output}"
                + " --model {model_id}"
                + " --format {format}"
                + " --namespace {namespace}"
                + " --max-elements 1024"
                + " --voxel-grid 28"
                + " --palette-size 16"
                + " --strict";
    }

    private ProcessBuilder processBuilderFor(String command) {
        return switch (shell) {
            case "cmd" -> new ProcessBuilder("cmd.exe", "/c", command);
            case "bash" -> new ProcessBuilder("bash", "-lc", command);
            default -> new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command);
        };
    }

    private String interpolate(String template, ConversionRequest request) {
        return template
                .replace("{input}", quote(request.sourceFile().toString()))
                .replace("{output}", quote(request.runtimeDirectory().toString()))
                .replace("{model_id}", request.modelId())
                .replace("{model_dir}", quote(request.modelDirectory().toString()))
                .replace("{plugin_dir}", quote(request.pluginDataDirectory().toString()))
                .replace("{runtime_dir}", quote(request.runtimeDirectory().toString()))
                .replace("{namespace}", namespace)
                .replace("{format}", request.format().name().toLowerCase(Locale.ROOT));
    }

    private static String normalizeNamespace(String raw) {
        if (raw == null || raw.isBlank()) {
            return "rvxmodels";
        }
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-'
                    || c == '.';
            out.append(valid ? c : '_');
        }
        return out.isEmpty() ? "rvxmodels" : out.toString();
    }

    private ConversionResult parseReport(Path reportPath, int exitCode, String output) {
        try {
            String json = Files.readString(reportPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean success = bool(root, "success", exitCode == 0);
            String message = string(root, "message", "converter_report");

            List<String> artifacts = toStringList(array(root, "artifacts"));
            List<String> warnings = toStringList(array(root, "warnings"));
            List<String> animations = toStringList(array(root, "animations"));
            if (!output.isBlank()) {
                warnings = new ArrayList<>(warnings);
                warnings.add(safeShort(output));
            }

            if (!success) {
                return ConversionResult.failure(name(), message);
            }
            return ConversionResult.success(name(), message, artifacts, warnings, animations);
        } catch (Exception ex) {
            return ConversionResult.failure(name(), "Invalid conversion-report.json: " + ex.getMessage());
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return object.getAsJsonArray(key);
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static List<String> toStringList(JsonArray arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static List<String> listArtifacts(Path runtimeDir, Path modelDir) {
        List<String> out = new ArrayList<>();
        if (!Files.exists(runtimeDir)) {
            return out;
        }
        try {
            Files.walk(runtimeDir)
                    .filter(Files::isRegularFile)
                    .forEach(path -> out.add(modelDir.relativize(path).toString().replace('\\', '/')));
        } catch (IOException ignored) {
        }
        return out;
    }

    private static String quote(String value) {
        if (value.indexOf(' ') >= 0) {
            return "\"" + value + "\"";
        }
        return value;
    }

    private static String safeShort(String raw) {
        String compact = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= 220) {
            return compact;
        }
        return compact.substring(0, 220) + "...";
    }
}
