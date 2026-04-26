package com.ravox.models.core.license;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class LicenseService {
    private final JavaPlugin plugin;
    private final boolean enabled;
    private final String apiUrl;
    private final String licenseKey;
    private final String product;
    private final long heartbeatMinutes;
    private final long graceHours;
    private final String serverId;
    private volatile boolean valid;
    private volatile long validUntilEpochMillis;

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
        this.valid = !enabled;
        this.validUntilEpochMillis = Long.MAX_VALUE;
    }

    public void start() {
        if (!enabled) {
            valid = true;
            return;
        }
        refreshNow();
        long periodTicks = TimeUnit.MINUTES.toSeconds(heartbeatMinutes) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshNow, periodTicks, periodTicks);
    }

    public boolean refreshNow() {
        if (!enabled) {
            valid = true;
            validUntilEpochMillis = Long.MAX_VALUE;
            return true;
        }
        boolean ok = verifyRemote();
        if (ok) {
            valid = true;
            validUntilEpochMillis = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(graceHours);
            return true;
        }
        if (System.currentTimeMillis() <= validUntilEpochMillis) {
            return true;
        }
        valid = false;
        return false;
    }

    public boolean isValid() {
        return valid;
    }

    public String statusLine() {
        if (!enabled) {
            return "license=disabled";
        }
        return "license=" + (valid ? "valid" : "invalid") + ", validUntil=" + validUntilEpochMillis;
    }

    private boolean verifyRemote() {
        if (apiUrl.isEmpty() || licenseKey.isEmpty()) {
            plugin.getLogger().warning("License enabled but api_url/key missing.");
            return false;
        }
        try {
            String query = "product=" + encode(product)
                    + "&key=" + encode(licenseKey)
                    + "&server_id=" + encode(serverId)
                    + "&plugin_version=" + encode(plugin.getDescription().getVersion());
            URL url = new URL(apiUrl + (apiUrl.contains("?") ? "&" : "?") + query);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            int code = con.getResponseCode();
            if (code != 200) {
                return false;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String payload = sb.toString().toLowerCase();
                return payload.contains("\"valid\":true") || payload.contains("\"ok\":true");
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("License check failed: " + ex.getMessage());
            return false;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
