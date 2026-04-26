package com.ravox.models.bridge.customore;

import com.ravox.models.api.ModelHandle;
import com.ravox.models.api.RavoxModelsApi;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class BossPhaseController {
    private final RavoxModelsApi api;
    private final int blendMillis;
    private final TreeMap<Double, String> thresholds = new TreeMap<>();
    private final Map<UUID, String> lastAnimationByHandle = new ConcurrentHashMap<>();

    BossPhaseController(RavoxModelsApi api, FileConfiguration config) {
        this.api = api;
        this.blendMillis = Math.max(0, config.getInt("phase.blend_millis", 350));
        ConfigurationSection section = config.getConfigurationSection("phase.thresholds");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    double threshold = Double.parseDouble(key);
                    thresholds.put(threshold, section.getString(key, ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    boolean updateBossPhase(ModelHandle handle, double currentHp, double maxHp) {
        if (maxHp <= 0) {
            return false;
        }
        double ratio = Math.max(0.0, Math.min(1.0, currentHp / maxHp));
        String next = resolveAnimationForRatio(ratio);
        if (next == null || next.isBlank()) {
            return false;
        }

        String normalized = api.normalizeKey(next);
        String last = lastAnimationByHandle.get(handle.id());
        if (normalized.equals(last)) {
            return false;
        }
        String current = api.getCurrentAnimation(handle);
        if (current == null || current.isBlank()) {
            api.playAnimation(handle, normalized, true);
        } else {
            api.transitionAnimation(handle, current, normalized, blendMillis, true);
        }
        lastAnimationByHandle.put(handle.id(), normalized);
        return true;
    }

    private String resolveAnimationForRatio(double ratio) {
        for (Map.Entry<Double, String> entry : thresholds.entrySet()) {
            if (ratio <= entry.getKey()) {
                return entry.getValue();
            }
        }
        return null;
    }
}
