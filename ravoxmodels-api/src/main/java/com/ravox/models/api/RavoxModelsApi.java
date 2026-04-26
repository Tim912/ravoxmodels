package com.ravox.models.api;

import org.bukkit.Location;

public interface RavoxModelsApi {
    ModelHandle spawnModel(String modelId, Location location);
    boolean playAnimation(ModelHandle handle, String animationName, boolean loop);
    boolean transitionAnimation(ModelHandle handle, String fromAnimation, String toAnimation, int blendMillis, boolean loop);
    boolean despawn(ModelHandle handle);
    boolean queueImport(String filename);
    boolean forceResourcePack(String playerName);
    String normalizeKey(String key);
    String getVersion();
}
