package com.ravox.models.core.runtime;

import com.ravox.models.api.ModelHandle;
import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;

public final class ActiveModel {
    private final ModelHandle handle;
    private final String modelId;
    private final Location spawnLocation;
    private final UUID displayEntityId;
    private final int customModelData;
    private String currentAnimation;
    private boolean looping;
    private String state;
    private boolean transitionActive;
    private String transitionFrom;
    private String transitionTo;
    private int transitionBlendMillis;
    private long transitionStartedAtMillis;

    public ActiveModel(ModelHandle handle, String modelId, Location spawnLocation, UUID displayEntityId, int customModelData) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.spawnLocation = spawnLocation.clone();
        this.displayEntityId = displayEntityId;
        this.customModelData = customModelData;
        this.currentAnimation = "";
        this.looping = false;
        this.state = "idle";
        this.transitionActive = false;
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

    public UUID getDisplayEntityId() {
        return displayEntityId;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public String getCurrentAnimation() {
        return currentAnimation;
    }

    public boolean isLooping() {
        return looping;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public boolean isTransitionActive() {
        return transitionActive;
    }

    public String getTransitionFrom() {
        return transitionFrom;
    }

    public String getTransitionTo() {
        return transitionTo;
    }

    public int getTransitionBlendMillis() {
        return transitionBlendMillis;
    }

    public long getTransitionStartedAtMillis() {
        return transitionStartedAtMillis;
    }

    public void play(String animation, boolean loop) {
        this.currentAnimation = animation;
        this.looping = loop;
        this.transitionActive = false;
    }

    public void transition(String from, String to, int blendMillis, boolean loop) {
        this.transitionActive = true;
        this.transitionFrom = from;
        this.transitionTo = to;
        this.transitionBlendMillis = Math.max(0, blendMillis);
        this.transitionStartedAtMillis = System.currentTimeMillis();
        this.looping = loop;
    }

    public void completeTransition() {
        if (!transitionActive) {
            return;
        }
        this.currentAnimation = transitionTo;
        this.transitionActive = false;
    }
}
