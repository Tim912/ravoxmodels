package com.ravox.models.core.resourcepack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.ravox.models.core.model.ModelDefinition;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ResourcePackService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final JavaPlugin plugin;
    private final Path packDir;
    private final boolean enabled;
    private final boolean force;
    private final String externalUrl;
    private final boolean hostEnabled;
    private final String hostPublic;
    private final int hostPort;
    private final String hostPath;
    private final int packFormat;
    private final String description;
    private final int joinSendDelayTicks;
    private final int retryDelayTicks;
    private final int maxDownloadRetries;
    private final long trackingTtlMillis;
    private final boolean safetyBlockLocalhostUrl;
    private final ConcurrentMap<UUID, DeliveryState> trackedPlayers = new ConcurrentHashMap<>();
    private volatile boolean localhostWarningShown;
    private HttpServer httpServer;
    private Path activeZip;
    private byte[] activeSha1;
    private String activeUrl;
    private long lastBuildAt;

    public ResourcePackService(JavaPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("resourcepack.enabled", true);
        this.force = cfg.getBoolean("resourcepack.force", true);
        this.externalUrl = cfg.getString("resourcepack.hosted_url", "").trim();
        this.hostEnabled = cfg.getBoolean("resourcepack.host.enabled", true);
        this.hostPublic = cfg.getString("resourcepack.host.public_host", "127.0.0.1");
        this.hostPort = cfg.getInt("resourcepack.host.port", 8777);
        this.hostPath = normalizePath(cfg.getString("resourcepack.host.path", "/ravoxmodels/pack.zip"));
        this.packFormat = cfg.getInt("resourcepack.pack_format", 84);
        this.description = cfg.getString("resourcepack.description", "RavoxModels generated pack");
        this.joinSendDelayTicks = Math.max(0, cfg.getInt("resourcepack.join_send_delay_ticks", 40));
        this.retryDelayTicks = Math.max(1, cfg.getInt("resourcepack.retry_delay_ticks", 60));
        this.maxDownloadRetries = Math.max(0, cfg.getInt("resourcepack.max_download_retries", 2));
        this.trackingTtlMillis = Math.max(10L, cfg.getLong("resourcepack.tracking_ttl_seconds", 300L)) * 1000L;
        this.safetyBlockLocalhostUrl = cfg.getBoolean("resourcepack.safety_block_localhost_url", true);
        this.packDir = plugin.getDataFolder().toPath().resolve("resourcepack");
    }

    public void start() {
        try {
            Files.createDirectories(packDir);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not create resourcepack directory: " + ex.getMessage());
            return;
        }
        if (!enabled) {
            return;
        }
        if (!externalUrl.isEmpty()) {
            activeUrl = externalUrl;
            warnIfLocalhostUrl();
            return;
        }
        if (!hostEnabled) {
            return;
        }
        try {
            httpServer = HttpServer.create(new InetSocketAddress(hostPort), 0);
            httpServer.createContext(hostPath, new PackHandler());
            httpServer.setExecutor(Executors.newFixedThreadPool(2));
            httpServer.start();
            activeUrl = "http://" + hostPublic + ":" + hostPort + hostPath;
            plugin.getLogger().info("Resourcepack host started at " + activeUrl);
            warnIfLocalhostUrl();
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not start resourcepack host: " + ex.getMessage());
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    public synchronized boolean buildPack(Iterable<ModelDefinition> models) {
        if (!enabled) {
            return false;
        }
        Path tempDir = packDir.resolve("build-temp");
        Path zipPath = packDir.resolve("ravoxmodels-pack.zip");
        Path tempZip = packDir.resolve("ravoxmodels-pack.tmp.zip");

        try {
            recreateDirectory(tempDir);
            writePackMeta(tempDir);
            writeModelEntries(tempDir, models);
            zipDirectory(tempDir, tempZip);
            Files.move(tempZip, zipPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            activeZip = zipPath;
            activeSha1 = sha1(zipPath);
            if (!externalUrl.isEmpty()) {
                activeUrl = externalUrl;
            } else if (activeUrl == null && hostEnabled) {
                activeUrl = "http://" + hostPublic + ":" + hostPort + hostPath;
            }
            lastBuildAt = Instant.now().toEpochMilli();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Resourcepack build failed: " + ex.getMessage());
            return false;
        } finally {
            try {
                deleteDirectory(tempDir);
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(tempZip);
            } catch (IOException ignored) {
            }
        }
    }

    public boolean applyToPlayer(Player player) {
        if (!enabled || activeUrl == null || activeSha1 == null || player == null) {
            return false;
        }
        if (safetyBlockLocalhostUrl && isLikelyLocalhostUrl(activeUrl)) {
            warnIfLocalhostUrl();
            return false;
        }
        player.setResourcePack(activeUrl, activeSha1);
        trackedPlayers.put(player.getUniqueId(), new DeliveryState(System.currentTimeMillis(), 0));
        return true;
    }

    public int applyToAll() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (applyToPlayer(player)) {
                count++;
            }
        }
        return count;
    }

    public boolean isForce() {
        return force;
    }

    public int getJoinSendDelayTicks() {
        return joinSendDelayTicks;
    }

    public boolean isTracking(Player player) {
        if (player == null) {
            return false;
        }
        DeliveryState state = trackedPlayers.get(player.getUniqueId());
        if (state == null) {
            return false;
        }
        if (System.currentTimeMillis() - state.sentAtMillis > trackingTtlMillis) {
            trackedPlayers.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void clearTracking(Player player) {
        if (player != null) {
            trackedPlayers.remove(player.getUniqueId());
        }
    }

    public FailureAction handleFailure(Player player, String statusName) {
        if (player == null || statusName == null) {
            return FailureAction.IGNORED;
        }
        UUID id = player.getUniqueId();
        DeliveryState state = trackedPlayers.get(id);
        if (state == null) {
            return FailureAction.IGNORED;
        }

        String normalized = statusName.toUpperCase(Locale.ROOT);
        if ("DECLINED".equals(normalized)) {
            trackedPlayers.remove(id);
            return FailureAction.KICK;
        }

        int failures = state.downloadFailures + 1;
        if (failures > maxDownloadRetries) {
            trackedPlayers.remove(id);
            return FailureAction.KICK;
        }

        trackedPlayers.put(id, new DeliveryState(System.currentTimeMillis(), failures));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline()) {
                return;
            }
            DeliveryState current = trackedPlayers.get(id);
            if (current == null || current.downloadFailures != failures) {
                return;
            }
            applyRetry(online, failures);
        }, retryDelayTicks);
        return FailureAction.RETRYING;
    }

    public String getActiveUrl() {
        return activeUrl;
    }

    public String getActiveSha1Hex() {
        if (activeSha1 == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : activeSha1) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    public long getLastBuildAt() {
        return lastBuildAt;
    }

    private void writePackMeta(Path tempDir) throws IOException {
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> pack = new HashMap<>();
        pack.put("pack_format", packFormat);
        pack.put("description", description);
        root.put("pack", pack);
        Path packMeta = tempDir.resolve("pack.mcmeta");
        Files.writeString(packMeta, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private void writeModelEntries(Path tempDir, Iterable<ModelDefinition> models) throws IOException {
        Path rvxModelDir = tempDir.resolve("assets/rvxmodels/models/item");
        Path rvxTextureDir = tempDir.resolve("assets/rvxmodels/textures/item");
        Path mcModelDir = tempDir.resolve("assets/minecraft/models/item");
        Files.createDirectories(rvxModelDir);
        Files.createDirectories(rvxTextureDir);
        Files.createDirectories(mcModelDir);

        Map<String, List<ModelDefinition>> byMaterial = new HashMap<>();
        for (ModelDefinition model : models) {
            byMaterial.computeIfAbsent(model.getMaterialKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(model);
            writeModelJson(rvxModelDir.resolve(model.getId() + ".json"), model.getId());
            writeTexture(rvxTextureDir.resolve(model.getId() + ".png"), model);
        }
        for (Map.Entry<String, List<ModelDefinition>> entry : byMaterial.entrySet()) {
            entry.getValue().sort(Comparator.comparingInt(ModelDefinition::getCustomModelData));
            writeMaterialOverrides(mcModelDir.resolve(entry.getKey() + ".json"), entry.getKey(), entry.getValue());
        }
    }

    private void writeModelJson(Path path, String modelId) throws IOException {
        Map<String, Object> root = new HashMap<>();
        root.put("parent", "item/generated");
        Map<String, String> textures = new HashMap<>();
        textures.put("layer0", "rvxmodels:item/" + modelId);
        root.put("textures", textures);
        Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private void writeMaterialOverrides(Path path, String material, List<ModelDefinition> models) throws IOException {
        Map<String, Object> root = new HashMap<>();
        root.put("parent", "item/generated");
        Map<String, String> textures = new HashMap<>();
        textures.put("layer0", "minecraft:item/" + material);
        root.put("textures", textures);

        List<Map<String, Object>> overrides = new ArrayList<>();
        for (ModelDefinition model : models) {
            Map<String, Object> override = new HashMap<>();
            Map<String, Number> predicate = new HashMap<>();
            predicate.put("custom_model_data", model.getCustomModelData());
            override.put("predicate", predicate);
            override.put("model", "rvxmodels:item/" + model.getId());
            overrides.add(override);
        }
        root.put("overrides", overrides);
        Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private void writeTexture(Path output, ModelDefinition model) throws IOException {
        if (model.getPreviewTexturePath() != null && !model.getPreviewTexturePath().isBlank()) {
            Path preview = plugin.getDataFolder().toPath().resolve(model.getPreviewTexturePath()).normalize();
            if (Files.exists(preview) && Files.isRegularFile(preview)) {
                Files.copy(preview, output, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        writePlaceholderTexture(output, model.getId());
    }

    private void writePlaceholderTexture(Path output, String seed) throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            int hash = seed.hashCode();
            int r = 80 + (Math.abs(hash) % 120);
            int gCol = 80 + (Math.abs(hash / 31) % 120);
            int b = 80 + (Math.abs(hash / 17) % 120);
            Color base = new Color(r, gCol, b);
            g.setColor(base);
            g.fillRect(0, 0, 64, 64);
            g.setColor(base.brighter());
            g.fillRect(0, 0, 64, 12);
            g.setColor(Color.BLACK);
            g.drawRect(0, 0, 63, 63);
            g.drawLine(0, 0, 63, 63);
            g.drawLine(63, 0, 0, 63);
        } finally {
            g.dispose();
        }
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void zipDirectory(Path sourceDir, Path outputZip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputZip))) {
            Files.walk(sourceDir)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String entryName = sourceDir.relativize(path).toString().replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
    }

    private static void recreateDirectory(Path dir) throws IOException {
        deleteDirectory(dir);
        Files.createDirectories(dir);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static byte[] sha1(Path file) throws IOException {
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
        return digest.digest();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/ravoxmodels/pack.zip";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private void applyRetry(Player player, int failures) {
        if (!enabled || activeUrl == null || activeSha1 == null) {
            return;
        }
        if (safetyBlockLocalhostUrl && isLikelyLocalhostUrl(activeUrl)) {
            warnIfLocalhostUrl();
            return;
        }
        player.setResourcePack(activeUrl, activeSha1);
        trackedPlayers.put(player.getUniqueId(), new DeliveryState(System.currentTimeMillis(), failures));
    }

    private void warnIfLocalhostUrl() {
        if (localhostWarningShown || activeUrl == null) {
            return;
        }
        if (!isLikelyLocalhostUrl(activeUrl)) {
            return;
        }
        localhostWarningShown = true;
        plugin.getLogger().warning("Resourcepack URL points to localhost (" + activeUrl + "). Remote players cannot download it.");
        plugin.getLogger().warning("Set resourcepack.host.public_host to a public domain/IP or set resourcepack.hosted_url.");
    }

    private static boolean isLikelyLocalhostUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("://127.0.0.1")
                || lower.contains("://localhost")
                || lower.contains("://[::1]");
    }

    private record DeliveryState(long sentAtMillis, int downloadFailures) {
    }

    public enum FailureAction {
        IGNORED,
        RETRYING,
        KICK
    }

    private final class PackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (activeZip == null || !Files.exists(activeZip)) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, Files.size(activeZip));
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(activeZip, out);
            }
        }
    }
}
