package com.ravox.models.core.importer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ravox.models.core.converter.ConversionRequest;
import com.ravox.models.core.converter.ConversionResult;
import com.ravox.models.core.converter.ConverterPipeline;
import com.ravox.models.core.model.ModelDefinition;
import com.ravox.models.core.model.ModelFormat;
import com.ravox.models.core.model.ModelIdUtil;
import com.ravox.models.core.model.ModelRegistry;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

public final class ImportService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_HISTORY = 100;

    private final JavaPlugin plugin;
    private final Path importDirectory;
    private final Path modelsDirectory;
    private final Set<String> acceptedExtensions;
    private final ImportLimits limits;
    private final String materialKey;
    private final boolean autoWatch;
    private final ModelRegistry registry;
    private final ConverterPipeline converterPipeline;
    private final GlbInspector glbInspector = new GlbInspector();
    private final FbxInspector fbxInspector = new FbxInspector();
    private final ExecutorService importerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ravoxmodels-importer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Deque<ImportRecord> history = new ArrayDeque<>();

    private volatile int queuedJobs;
    private volatile Consumer<ModelDefinition> successCallback;
    private WatchService watchService;
    private Thread watchThread;

    public ImportService(JavaPlugin plugin, FileConfiguration config) throws IOException {
        this.plugin = plugin;
        this.importDirectory = resolvePath(plugin.getDataFolder().toPath(), config.getString("import.directory", "plugins/RavoxModels/import"));
        this.modelsDirectory = plugin.getDataFolder().toPath().resolve("models");
        this.acceptedExtensions = readExtensions(config.getStringList("import.accepted_extensions"));
        this.limits = ImportLimits.fromConfig(
                config.getInt("limits.max_triangles", 15000),
                config.getInt("limits.max_bones", 32),
                config.getInt("limits.max_texture_size", 2048)
        );
        this.materialKey = config.getString("runtime.item_material", "STICK").toUpperCase(Locale.ROOT);
        this.autoWatch = config.getBoolean("import.auto_watch", true);
        this.converterPipeline = new ConverterPipeline(plugin, config);

        Files.createDirectories(importDirectory);
        Files.createDirectories(modelsDirectory);

        this.registry = new ModelRegistry(modelsDirectory.resolve("index.json"));
        this.registry.load();
    }

    public void setSuccessCallback(Consumer<ModelDefinition> successCallback) {
        this.successCallback = successCallback;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (!autoWatch) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            importDirectory.register(watchService, ENTRY_CREATE);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not start import watcher: " + ex.getMessage());
            return;
        }
        watchThread = new Thread(this::watchLoop, "ravoxmodels-import-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    public void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        importerExecutor.shutdownNow();
    }

    public boolean queue(String filename) {
        Path source = importDirectory.resolve(filename).normalize();
        if (!source.startsWith(importDirectory) || !Files.exists(source) || !Files.isRegularFile(source)) {
            addHistory(new ImportRecord(now(), ImportStatus.FAILED, filename, null, "File not found in import directory."));
            return false;
        }
        String ext = extension(source.getFileName().toString());
        if (!acceptedExtensions.contains(ext)) {
            addHistory(new ImportRecord(now(), ImportStatus.FAILED, filename, null, "Unsupported extension: " + ext));
            return false;
        }

        queuedJobs++;
        addHistory(new ImportRecord(now(), ImportStatus.ACCEPTED, filename, null, "Queued"));
        importerExecutor.submit(() -> processImport(source));
        return true;
    }

    public List<ImportRecord> recentHistory(int limit) {
        synchronized (history) {
            List<ImportRecord> out = new ArrayList<>();
            int c = 0;
            for (ImportRecord record : history) {
                out.add(record);
                c++;
                if (c >= limit) {
                    break;
                }
            }
            return out;
        }
    }

    public Collection<ModelDefinition> allModels() {
        return registry.all();
    }

    public ModelRegistry getRegistry() {
        return registry;
    }

    public Path getImportDirectory() {
        return importDirectory;
    }

    public Path getModelsDirectory() {
        return modelsDirectory;
    }

    public int getQueuedJobs() {
        return queuedJobs;
    }

    private void processImport(Path source) {
        try {
            String filename = source.getFileName().toString();
            String ext = extension(filename);
            InspectionResult inspection;
            if ("glb".equals(ext)) {
                inspection = glbInspector.inspect(source, limits);
            } else if ("fbx".equals(ext)) {
                inspection = fbxInspector.inspect(source, limits);
            } else {
                addHistory(new ImportRecord(now(), ImportStatus.FAILED, filename, null, "Unsupported extension: " + ext));
                return;
            }

            if (!inspection.isValid()) {
                addHistory(new ImportRecord(now(), ImportStatus.FAILED, filename, null, inspection.getMessage()));
                return;
            }

            String baseId = ModelIdUtil.sanitize(stripExtension(filename));
            String modelId = ModelIdUtil.unique(baseId, registry.ids());
            Path modelDir = modelsDirectory.resolve(modelId);
            recreateDirectory(modelDir);

            Path sourceTarget = modelDir.resolve("source." + ext);
            Files.copy(source, sourceTarget, StandardCopyOption.REPLACE_EXISTING);
            Path runtimeDir = modelDir.resolve("runtime");
            Files.createDirectories(runtimeDir);

            String sha1 = sha1(sourceTarget);
            String previewTexturePath = null;
            byte[] previewPng = inspection.getPreviewTexturePng();
            if (previewPng != null && previewPng.length > 0) {
                Path previewPath = modelDir.resolve("preview.png");
                Files.write(previewPath, previewPng, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                previewTexturePath = relativeToData(previewPath);
            }

            ConversionResult conversion = converterPipeline.convert(new ConversionRequest(
                    modelId,
                    inspection.getFormat(),
                    sourceTarget,
                    modelDir,
                    runtimeDir,
                    plugin.getDataFolder().toPath()
            ));
            if (!conversion.isSuccess()) {
                addHistory(new ImportRecord(now(), ImportStatus.FAILED, filename, null, "Converter failed: " + conversion.getMessage()));
                return;
            }

            Path manifestPath = modelDir.resolve("manifest.json");
            int customModelData = customModelDataFor(modelId);
            List<String> animationKeys;
            if (!conversion.getAnimationNames().isEmpty()) {
                animationKeys = sanitizeAnimations(conversion.getAnimationNames());
            } else {
                animationKeys = sanitizeAnimations(inspection.getAnimationNames());
            }
            List<String> warnings = new ArrayList<>(inspection.getWarnings());
            warnings.addAll(conversion.getWarnings());
            ModelDefinition definition = new ModelDefinition(
                    modelId,
                    inspection.getFormat(),
                    filename,
                    sha1,
                    now(),
                    inspection.getEstimatedTriangles(),
                    inspection.getSkinBones(),
                    inspection.getMaxTextureSize(),
                    animationKeys.size(),
                    animationKeys,
                    customModelData,
                    materialKey,
                    relativeToData(modelDir),
                    relativeToData(manifestPath),
                    previewTexturePath,
                    !"noop".equalsIgnoreCase(conversion.getBackendName()),
                    conversion.getBackendName(),
                    conversion.getArtifacts(),
                    warnings
            );

            writeManifest(manifestPath, definition);
            registry.upsert(definition);
            registry.save();

            Consumer<ModelDefinition> callback = successCallback;
            if (callback != null) {
                callback.accept(definition);
            }
            addHistory(new ImportRecord(
                    now(),
                    ImportStatus.SUCCESS,
                    filename,
                    modelId,
                    "Imported via " + conversion.getBackendName() + ": " + conversion.getMessage()
            ));
        } catch (Exception ex) {
            addHistory(new ImportRecord(now(), ImportStatus.FAILED, source.getFileName().toString(), null, ex.getMessage()));
            plugin.getLogger().warning("Import failed for " + source.getFileName() + ": " + ex.getMessage());
        } finally {
            queuedJobs = Math.max(0, queuedJobs - 1);
        }
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind != ENTRY_CREATE) {
                    continue;
                }
                Path child = importDirectory.resolve((Path) event.context());
                String filename = child.getFileName().toString();
                String ext = extension(filename);
                if (!acceptedExtensions.contains(ext)) {
                    continue;
                }
                plugin.getLogger().info("Detected new import file: " + filename);
                queue(filename);
            }
            key.reset();
        }
    }

    private static Set<String> readExtensions(List<String> configured) {
        Set<String> out = new HashSet<>();
        for (String ext : configured) {
            if (ext == null || ext.isBlank()) {
                continue;
            }
            out.add(ext.toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) {
            out.add("glb");
            out.add("fbx");
        }
        return out;
    }

    private static Path resolvePath(Path dataFolder, String configuredPath) {
        Path configured = Path.of(configuredPath);
        if (configured.isAbsolute()) {
            return configured;
        }
        Path pluginsFolder = dataFolder.getParent();
        if (pluginsFolder == null) {
            return dataFolder.resolve(configuredPath);
        }
        if (configuredPath.startsWith("plugins/") || configuredPath.startsWith("plugins\\")) {
            Path serverRoot = pluginsFolder.getParent();
            if (serverRoot != null) {
                return serverRoot.resolve(configuredPath);
            }
        }
        return pluginsFolder.resolve(configuredPath);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= filename.length()) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return filename;
        }
        return filename.substring(0, dot);
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    private void addHistory(ImportRecord record) {
        synchronized (history) {
            history.addFirst(record);
            while (history.size() > MAX_HISTORY) {
                history.removeLast();
            }
        }
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.createDirectories(directory);
    }

    private static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-1 unavailable", ex);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder out = new StringBuilder();
        for (byte b : hash) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }

    private int customModelDataFor(String modelId) {
        int hash = modelId.hashCode();
        if (hash == Integer.MIN_VALUE) {
            hash = 1;
        }
        int candidate = Math.max(1, Math.abs(hash));
        Set<Integer> used = new HashSet<>();
        for (ModelDefinition definition : registry.all()) {
            used.add(definition.getCustomModelData());
        }
        while (used.contains(candidate)) {
            candidate++;
            if (candidate == Integer.MAX_VALUE) {
                candidate = 1;
            }
        }
        return candidate;
    }

    private List<String> sanitizeAnimations(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            out.add(raw.toLowerCase(Locale.ROOT).replace(' ', '_'));
        }
        return out;
    }

    private void writeManifest(Path manifestPath, ModelDefinition definition) throws IOException {
        String json = GSON.toJson(definition);
        Files.writeString(manifestPath, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private String relativeToData(Path path) {
        return plugin.getDataFolder().toPath().relativize(path).toString().replace('\\', '/');
    }
}
