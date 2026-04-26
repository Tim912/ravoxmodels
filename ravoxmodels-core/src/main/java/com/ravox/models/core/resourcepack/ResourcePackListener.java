package com.ravox.models.core.resourcepack;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public final class ResourcePackListener implements Listener {
    private final ResourcePackService service;
    private final boolean autoApplyOnJoin;
    private final boolean force;

    public ResourcePackListener(ResourcePackService service, boolean autoApplyOnJoin, boolean force) {
        this.service = service;
        this.autoApplyOnJoin = autoApplyOnJoin;
        this.force = force;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!autoApplyOnJoin) {
            return;
        }
        service.applyToPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!force) {
            return;
        }
        switch (event.getStatus()) {
            case DECLINED, FAILED_DOWNLOAD -> event.getPlayer().kickPlayer("Resourcepack required: RavoxModels");
            default -> {
            }
        }
    }
}
