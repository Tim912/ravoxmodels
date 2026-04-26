package com.ravox.models.core.runtime;

import com.ravox.models.api.ModelHandle;
import com.ravox.models.core.model.ModelDefinition;
import com.ravox.models.core.model.ModelRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelRuntime {
    private final JavaPlugin plugin;
    private final ModelRegistry registry;
    private final Map<UUID, ActiveModel> models = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    public ModelRuntime(JavaPlugin plugin, ModelRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void start() {
        if (tickTask != null) {
            return;
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (ActiveModel model : new ArrayList<>(models.values())) {
            despawn(model.getHandle());
        }
    }

    public ModelHandle spawn(String modelId, Location location) {
        ModelDefinition definition = registry.find(modelId).orElse(null);
        if (definition == null || location.getWorld() == null) {
            return null;
        }
        UUID entityId = spawnDisplayEntity(location, definition.getMaterialKey(), definition.getCustomModelData());
        ModelHandle handle = new ModelHandle(UUID.randomUUID());
        ActiveModel active = new ActiveModel(handle, modelId, location, entityId, definition.getCustomModelData());
        models.put(handle.id(), active);
        return handle;
    }

    public boolean play(ModelHandle handle, String animationKey, boolean loop) {
        ActiveModel model = models.get(handle.id());
        if (model == null) {
            return false;
        }
        model.play(animationKey, loop);
        return true;
    }

    public boolean transition(ModelHandle handle, String fromAnimation, String toAnimation, int blendMillis, boolean loop) {
        ActiveModel model = models.get(handle.id());
        if (model == null) {
            return false;
        }
        model.transition(fromAnimation, toAnimation, blendMillis, loop);
        return true;
    }

    public boolean setState(ModelHandle handle, String state) {
        ActiveModel model = models.get(handle.id());
        if (model == null) {
            return false;
        }
        model.setState(state);
        return true;
    }

    public boolean despawn(ModelHandle handle) {
        ActiveModel removed = models.remove(handle.id());
        if (removed == null) {
            return false;
        }
        UUID entityId = removed.getDisplayEntityId();
        if (entityId != null) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
        return true;
    }

    public ActiveModel find(UUID id) {
        return models.get(id);
    }

    public Collection<ActiveModel> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(models.values()));
    }

    private UUID spawnDisplayEntity(Location location, String materialKey, int customModelData) {
        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setItemStack(createDisplayItem(materialKey, customModelData));
        });
        return display.getUniqueId();
    }

    private ItemStack createDisplayItem(String materialKey, int customModelData) {
        Material material = Material.matchMaterial(materialKey);
        if (material == null && materialKey != null) {
            int separator = materialKey.indexOf(':');
            if (separator >= 0 && separator + 1 < materialKey.length()) {
                material = Material.matchMaterial(materialKey.substring(separator + 1));
            }
        }
        if (material == null || material.isAir()) {
            material = Material.STICK;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of((float) customModelData));
            meta.setCustomModelDataComponent(component);
            // Keep legacy integer in sync for older client/tooling compatibility.
            meta.setCustomModelData(customModelData);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (ActiveModel model : models.values()) {
            if (!model.isTransitionActive()) {
                continue;
            }
            int blendMillis = model.getTransitionBlendMillis();
            if (blendMillis <= 0 || now - model.getTransitionStartedAtMillis() >= blendMillis) {
                model.completeTransition();
            }
        }
    }
}
