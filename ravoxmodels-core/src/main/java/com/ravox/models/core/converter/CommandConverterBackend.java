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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        this.shell = config.getString("converter.command.shell", "auto").trim().toLowerCase(Locale.ROOT);
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

        Path modelDir = request.modelDirectory().toAbsolutePath().normalize();
        Path runtimeDir = request.runtimeDirectory().toAbsolutePath().normalize();
        try {
            Files.createDirectories(runtimeDir);
        } catch (IOException ex) {
            return ConversionResult.failure(name(), "Could not create runtime directory: " + ex.getMessage());
        }

        String command = interpolate(template, request);
        Path converterOutput = runtimeDir.resolve("converter-output.log");
        String output;
        int exitCode;
        Process process = null;
        IOException lastLaunchError = null;
        List<String> shellCandidates = shellCandidates();
        for (String candidateShell : shellCandidates) {
            ProcessBuilder builder = processBuilderFor(command, candidateShell);
            builder.directory(modelDir.toFile());
            configureConverterEnvironment(builder, request);
            builder.redirectErrorStream(true);
            builder.redirectOutput(converterOutput.toFile());
            try {
                Files.deleteIfExists(converterOutput);
                process = builder.start();
                boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    output = readProcessOutput(converterOutput);
                    return ConversionResult.failure(name(), "Converter timed out after " + timeoutSeconds + "s: " + safeShort(output));
                }
                exitCode = process.exitValue();
                output = readProcessOutput(converterOutput);
                if (!candidateShell.equals(shell) && !"auto".equals(shell)) {
                    plugin.getLogger().warning("Converter shell fallback: " + shell + " -> " + candidateShell);
                }
                return finishConversion(runtimeDir, request, exitCode, output);
            } catch (IOException ex) {
                lastLaunchError = ex;
            } catch (InterruptedException ex) {
                if (process != null) {
                    process.destroyForcibly();
                }
                Thread.currentThread().interrupt();
                return ConversionResult.failure(name(), "Converter interrupted.");
            }
        }
        String launchMessage = lastLaunchError == null ? "unknown launch error" : lastLaunchError.getMessage();
        return ConversionResult.failure(name(), "Converter failed to run (shells: " + String.join(", ", shellCandidates) + "): " + launchMessage);
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
        String normalized = raw;
        if (normalized.contains("converter_backend.py") && (!normalized.contains("--max-elements")
                || !raw.contains("--model-mode")
                || raw.contains("--model-mode rendered_cross")
                || (raw.contains("--max-elements 256") && raw.contains("--voxel-grid 20"))
                || (raw.contains("--max-elements 512") && raw.contains("--voxel-grid 24"))
                || raw.contains("{plugin_dir}/tools/converter_backend.py")
                || raw.contains("{plugin_dir}\\tools\\converter_backend.py"))) {
            plugin.getLogger().info("Upgrading bundled converter command for " + format + " at runtime.");
            normalized = defaultBundledCommand() + extractBlenderOption(raw);
        }
        return normalizePythonLauncher(normalized);
    }

    private static String defaultBundledCommand() {
        return defaultPythonLauncher() + " {converter_backend}"
                + " --input {input}"
                + " --output {output}"
                + " --model {model_id}"
                + " --format {format}"
                + " --namespace {namespace}"
                + " --max-elements 1024"
                + " --voxel-grid 30"
                + " --palette-size 32"
                + " --model-mode rendered_box"
                + " --strict";
    }

    private static String extractBlenderOption(String raw) {
        Matcher matcher = Pattern.compile("--blender\\s+(\"[^\"]+\"|'[^']+'|\\S+)").matcher(raw);
        if (!matcher.find()) {
            return "";
        }
        return " --blender " + matcher.group(1);
    }

    private ProcessBuilder processBuilderFor(String command, String shellName) {
        return switch (shellName) {
            case "cmd" -> new ProcessBuilder("cmd.exe", "/c", command);
            case "bash" -> new ProcessBuilder("bash", "-lc", command);
            case "sh" -> new ProcessBuilder("sh", "-lc", command);
            default -> new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command);
        };
    }

    private void configureConverterEnvironment(ProcessBuilder builder, ConversionRequest request) {
        Path home = request.pluginDataDirectory().toAbsolutePath().normalize().resolve("runtime-home");
        Path config = home.resolve(".config");
        Path cache = home.resolve(".cache");
        Path data = home.resolve(".local").resolve("share");
        Path scripts = home.resolve("scripts");
        Path temp = home.resolve("tmp");
        try {
            Files.createDirectories(config);
            Files.createDirectories(cache);
            Files.createDirectories(data);
            Files.createDirectories(scripts);
            Files.createDirectories(temp);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not prepare converter runtime home: " + ex.getMessage());
        }

        var env = builder.environment();
        if (isWindowsRuntime()) {
            env.putIfAbsent("USERPROFILE", home.toString());
            env.putIfAbsent("TEMP", temp.toString());
            env.putIfAbsent("TMP", temp.toString());
        } else {
            env.put("HOME", home.toString());
            env.put("XDG_CONFIG_HOME", config.toString());
            env.put("XDG_CACHE_HOME", cache.toString());
            env.put("XDG_DATA_HOME", data.toString());
            env.put("TMPDIR", temp.toString());
        }
        env.put("BLENDER_USER_CONFIG", config.resolve("blender").toString());
        env.put("BLENDER_USER_SCRIPTS", scripts.toString());
        env.put("BLENDER_USER_DATAFILES", data.resolve("blender").toString());
    }

    private List<String> shellCandidates() {
        Set<String> candidates = new LinkedHashSet<>();
        boolean windows = isWindowsRuntime();
        if ("auto".equals(shell)) {
            if (windows) {
                candidates.add("powershell");
                candidates.add("cmd");
            } else {
                candidates.add("sh");
                candidates.add("bash");
            }
        } else {
            candidates.add(shell);
            if (windows) {
                candidates.add("powershell");
                candidates.add("cmd");
            } else {
                candidates.add("sh");
                candidates.add("bash");
            }
        }
        return new ArrayList<>(candidates);
    }

    private ConversionResult finishConversion(Path runtimeDir, ConversionRequest request, int exitCode, String output) {
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

    private String interpolate(String template, ConversionRequest request) {
        String normalizedTemplate = template
                .replace("{plugin_dir}/tools/converter_backend.py", "{converter_backend}")
                .replace("{plugin_dir}\\tools\\converter_backend.py", "{converter_backend}")
                .replace("{plugin_dir}/tools/converter_blender_bridge.py", "{converter_blender_bridge}")
                .replace("{plugin_dir}\\tools\\converter_blender_bridge.py", "{converter_blender_bridge}")
                .replace("plugins/RavoxModels/tools/converter_backend.py", "{converter_backend}")
                .replace("plugins\\RavoxModels\\tools\\converter_backend.py", "{converter_backend}")
                .replace("tools/converter_backend.py", "{converter_backend}")
                .replace("tools\\converter_backend.py", "{converter_backend}")
                .replace("plugins/RavoxModels/tools/converter_blender_bridge.py", "{converter_blender_bridge}")
                .replace("plugins\\RavoxModels\\tools\\converter_blender_bridge.py", "{converter_blender_bridge}")
                .replace("tools/converter_blender_bridge.py", "{converter_blender_bridge}")
                .replace("tools\\converter_blender_bridge.py", "{converter_blender_bridge}");
        Path pluginDir = request.pluginDataDirectory().toAbsolutePath().normalize();
        Path modelDir = request.modelDirectory().toAbsolutePath().normalize();
        Path runtimeDir = request.runtimeDirectory().toAbsolutePath().normalize();
        Path input = request.sourceFile().toAbsolutePath().normalize();
        Path toolsDir = pluginDir.resolve("tools").toAbsolutePath().normalize();
        return normalizedTemplate
                .replace("{converter_backend}", quote(toolsDir.resolve("converter_backend.py").toString()))
                .replace("{converter_blender_bridge}", quote(toolsDir.resolve("converter_blender_bridge.py").toString()))
                .replace("{input}", quote(input.toString()))
                .replace("{output}", quote(runtimeDir.toString()))
                .replace("{model_id}", request.modelId())
                .replace("{model_dir}", quote(modelDir.toString()))
                .replace("{plugin_dir}", quote(pluginDir.toString()))
                .replace("{runtime_dir}", quote(runtimeDir.toString()))
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
        Path runtimeBase = runtimeDir.toAbsolutePath().normalize();
        Path modelBase = modelDir.toAbsolutePath().normalize();
        try {
            Files.walk(runtimeBase)
                    .filter(Files::isRegularFile)
                    .forEach(path -> out.add(modelBase.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')));
        } catch (IOException ignored) {
        }
        return out;
    }

    private static String readProcessOutput(Path outputPath) {
        if (!Files.exists(outputPath)) {
            return "";
        }
        try {
            return Files.readString(outputPath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "Could not read converter output: " + ex.getMessage();
        }
    }

    private static String quote(String value) {
        if (value.indexOf(' ') >= 0) {
            return "\"" + value + "\"";
        }
        return value;
    }

    private static String normalizePythonLauncher(String command) {
        String trimmed = command.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (isWindowsRuntime()) {
            if (lower.startsWith("python3 ")) {
                return "py -3 " + trimmed.substring("python3 ".length());
            }
            if (lower.startsWith("python ")) {
                return "py -3 " + trimmed.substring("python ".length());
            }
            return command;
        }
        if (lower.startsWith("py -3 ")) {
            return "python3 " + trimmed.substring("py -3 ".length());
        }
        return command;
    }

    private static String defaultPythonLauncher() {
        return isWindowsRuntime() ? "py -3" : "python3";
    }

    private static boolean isWindowsRuntime() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String safeShort(String raw) {
        String compact = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= 220) {
            return compact;
        }
        return compact.substring(0, 220) + "...";
    }
}
