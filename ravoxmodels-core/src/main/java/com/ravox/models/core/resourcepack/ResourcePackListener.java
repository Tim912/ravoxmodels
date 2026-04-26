package com.ravox.models.core.resourcepack;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class ResourcePackListener implements Listener {
    private final JavaPlugin plugin;
    private final ResourcePackService service;
    private final boolean autoApplyOnJoin;
    private final boolean force;

    public ResourcePackListener(JavaPlugin plugin, ResourcePackService service, boolean autoApplyOnJoin, boolean force) {
        this.plugin = plugin;
        this.service = service;
        this.autoApplyOnJoin = autoApplyOnJoin;
        this.force = force;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!autoApplyOnJoin) {
            return;
        }
        Player player = event.getPlayer();
        int delay = service.getJoinSendDelayTicks();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            boolean sent = service.applyToPlayer(player);
            if (!sent) {
                plugin.getLogger().warning("Resourcepack was not sent to " + player.getName() + ". Check URL and configuration.");
            }
        }, delay);
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!force) {
            return;
        }
        Player player = event.getPlayer();
        if (!service.isTracking(player)) {
            return;
        }

        String status = event.getStatus().name().toUpperCase(Locale.ROOT);
        if (isSuccessStatus(status)) {
            service.clearTracking(player);
            return;
        }
        if ("ACCEPTED".equals(status)) {
            return;
        }

        ResourcePackService.FailureAction action = service.handleFailure(player, status);
        switch (action) {
            case RETRYING -> player.sendMessage("Resourcepack download failed, retrying...");
            case KICK -> player.kickPlayer("Resourcepack required: RavoxModels");
            case IGNORED -> {
            }
        }
    }

    private static boolean isSuccessStatus(String status) {
        return "SUCCESSFULLY_LOADED".equals(status)
                || "DOWNLOADED".equals(status);
    }
}
