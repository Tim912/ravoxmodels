package com.ravox.models.core.runtime;

import com.ravox.models.api.ModelHandle;
import org.bukkit.Location;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelRuntime {
    private final Map<UUID, ActiveModel> models = new ConcurrentHashMap<>();

    public ModelHandle spawn(String modelId, Location location) {
        ModelHandle handle = new ModelHandle(UUID.randomUUID());
        models.put(handle.id(), new ActiveModel(handle, modelId, location));
        return handle;
    }

    public boolean play(ModelHandle handle, String animationKey) {
        ActiveModel model = models.get(handle.id());
        if (model == null) {
            return false;
        }
        model.setCurrentAnimation(animationKey);
        return true;
    }

    public boolean transition(ModelHandle handle, String toAnimationKey) {
        return play(handle, toAnimationKey);
    }

    public boolean despawn(ModelHandle handle) {
        return models.remove(handle.id()) != null;
    }

    public ActiveModel find(UUID id) {
        return models.get(id);
    }

    public Collection<ActiveModel> all() {
        return Collections.unmodifiableCollection(models.values());
    }
}
