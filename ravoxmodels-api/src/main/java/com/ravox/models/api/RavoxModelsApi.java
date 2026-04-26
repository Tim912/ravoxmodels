package com.ravox.models.api;

import org.bukkit.Location;

public interface RavoxModelsApi {
    ModelHandle spawnModel(String modelId, Location location);
    boolean playAnimation(ModelHandle handle, String animationName, boolean loop);
    boolean despawn(ModelHandle handle);
    String getVersion();
}
