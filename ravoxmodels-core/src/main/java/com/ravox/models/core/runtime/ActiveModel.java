package com.ravox.models.core.runtime;

import com.ravox.models.api.ModelHandle;
import org.bukkit.Location;

public final class ActiveModel {
    private final ModelHandle handle;
    private final String modelId;
    private final Location spawnLocation;
    private String currentAnimation;

    public ActiveModel(ModelHandle handle, String modelId, Location spawnLocation) {
        this.handle = handle;
        this.modelId = modelId;
        this.spawnLocation = spawnLocation.clone();
        this.currentAnimation = "";
    }

    public ModelHandle getHandle() {
        return handle;
    }

    public String getModelId() {
        return modelId;
    }

    public Location getSpawnLocation() {
        return spawnLocation.clone();
    }

    public String getCurrentAnimation() {
        return currentAnimation;
    }

    public void setCurrentAnimation(String currentAnimation) {
        this.currentAnimation = currentAnimation;
    }
}
