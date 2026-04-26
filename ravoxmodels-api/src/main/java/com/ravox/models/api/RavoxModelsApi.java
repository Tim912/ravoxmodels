package com.ravox.models.api;

import org.bukkit.Location;

import java.util.List;

public interface RavoxModelsApi {
    ModelHandle spawnModel(String modelId, Location location);
    boolean playAnimation(ModelHandle handle, String animationName, boolean loop);
    boolean transitionAnimation(ModelHandle handle, String fromAnimation, String toAnimation, int blendMillis, boolean loop);
    boolean despawn(ModelHandle handle);
    boolean setState(ModelHandle handle, String state);
    boolean queueImport(String filename);
    boolean forceResourcePack(String playerName);
    boolean modelExists(String modelId);
    List<String> listModelIds();
    String getCurrentAnimation(ModelHandle handle);
    String normalizeKey(String key);
    String getVersion();
}
