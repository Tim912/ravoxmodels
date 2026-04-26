package com.ravox.models.core.license;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class LicenseService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final JavaPlugin plugin;
    private final boolean enabled;
    private final String apiUrl;
    private final String licenseKey;
    private final String product;
    private final long heartbeatMinutes;
    private final long graceHours;
    private final String serverId;
    private final int timeoutMillis;
    private final HttpClient httpClient;
    private final Path cachePath;

    private volatile boolean valid;
    private volatile long validUntilEpochMillis;
    private volatile String lastStatusMessage;
    private BukkitTask heartbeatTask;

    public LicenseService(JavaPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("license.enabled", false);
        this.apiUrl = cfg.getString("license.api_url", "").trim();
        this.licenseKey = cfg.getString("license.key", "").trim();
        this.product = cfg.getString("license.product", "ravoxmodels").trim();
        this.heartbeatMinutes = Math.max(1L, cfg.getLong("license.heartbeat_minutes", 30L));
        this.graceHours = Math.max(1L, cfg.getLong("license.grace_hours", 48L));
        this.serverId = cfg.getString("license.server_id", UUID.randomUUID().toString()).trim();
        this.timeoutMillis = Math.max(1000, cfg.getInt("license.timeout_ms", 5000));
        this.httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(timeoutMillis)).build();
        this.cachePath = plugin.getDataFolder().toPath().resolve("license-cache.json");
        this.valid = !enabled;
        this.validUntilEpochMillis = enabled ? 0L : Long.MAX_VALUE;
        this.lastStatusMessage = enabled ? "not_checked" : "disabled";
    }

    public void start() {
        if (!enabled) {
            valid = true;
            validUntilEpochMillis = Long.MAX_VALUE;
            lastStatusMessage = "disabled";
            return;
        }

        loadCache();
        refreshNow();
        long periodTicks = TimeUnit.MINUTES.toSeconds(heartbeatMinutes) * 20L;
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshNow, periodTicks, periodTicks);
    }

    public void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
    }

    public boolean refreshNow() {
        if (!enabled) {
            valid = true;
            validUntilEpochMillis = Long.MAX_VALUE;
            lastStatusMessage = "disabled";
            return true;
        }

        RemoteResult remote = verifyRemote();
        long now = Instant.now().toEpochMilli();
        if (remote.success && remote.valid) {
            valid = true;
            validUntilEpochMillis = now + TimeUnit.HOURS.toMillis(remote.graceHours > 0 ? remote.graceHours : graceHours);
            lastStatusMessage = "remote_valid";
            saveCache();
            return true;
        }

        if (now <= validUntilEpochMillis) {
            valid = true;
            lastStatusMessage = "grace_active(" + remote.message + ")";
            return true;
        }

        valid = false;
        lastStatusMessage = "invalid(" + remote.message + ")";
        return false;
    }

    public boolean isValid() {
        return valid;
    }

    public String statusLine() {
        if (!enabled) {
            return "license=disabled";
        }
        return "license=" + (valid ? "valid" : "invalid")
                + ", validUntil=" + validUntilEpochMillis
                + ", state=" + lastStatusMessage;
    }

    private RemoteResult verifyRemote() {
        if (apiUrl.isBlank() || licenseKey.isBlank()) {
            return new RemoteResult(false, false, graceHours, "missing_api_or_key");
        }
        String query = "product=" + encode(product)
                + "&key=" + encode(licenseKey)
                + "&server_id=" + encode(serverId)
                + "&plugin_version=" + encode(plugin.getDescription().getVersion());
        String fullUrl = apiUrl + (apiUrl.contains("?") ? "&" : "?") + query;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(java.time.Duration.ofMillis(timeoutMillis))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                return new RemoteResult(false, false, graceHours, "http_" + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (Exception ex) {
            return new RemoteResult(false, false, graceHours, "request_failed");
        }
    }

    private RemoteResult parseResponse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            boolean valid = boolOr(root, "valid", boolOr(root, "ok", false));
            long remoteGrace = longOr(root, "grace_hours", graceHours);
            String message = stringOr(root, "message", valid ? "ok" : "denied");
            return new RemoteResult(true, valid, remoteGrace, message);
        } catch (RuntimeException ex) {
            String lower = body.toLowerCase(Locale.ROOT);
            boolean valid = lower.contains("\"valid\":true") || lower.contains("\"ok\":true");
            return new RemoteResult(true, valid, graceHours, valid ? "fallback_ok" : "fallback_invalid");
        }
    }

    private void saveCache() {
        try {
            Files.createDirectories(cachePath.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("validUntilEpochMillis", validUntilEpochMillis);
            obj.addProperty("lastStatus", lastStatusMessage);
            Files.writeString(cachePath, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not write license cache: " + ex.getMessage());
        }
    }

    private void loadCache() {
        if (!Files.exists(cachePath)) {
            return;
        }
        try {
            String raw = Files.readString(cachePath, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            validUntilEpochMillis = longOr(obj, "validUntilEpochMillis", 0L);
            lastStatusMessage = stringOr(obj, "lastStatus", "cache_loaded");
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read license cache: " + ex.getMessage());
        }
    }

    private static boolean boolOr(JsonObject obj, String key, boolean fallback) {
        if (obj == null || !obj.has(key)) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static long longOr(JsonObject obj, String key, long fallback) {
        if (obj == null || !obj.has(key)) {
            return fallback;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key)) {
            return fallback;
        }
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record RemoteResult(boolean success, boolean valid, long graceHours, String message) {
    }
}
