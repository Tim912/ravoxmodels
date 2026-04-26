package com.ravox.models.core;

import com.ravox.models.api.ModelHandle;
import com.ravox.models.api.RavoxModelsApi;
import com.ravox.models.core.importer.ImportService;
import com.ravox.models.core.license.LicenseService;
import com.ravox.models.core.resourcepack.ResourcePackListener;
import com.ravox.models.core.resourcepack.ResourcePackService;
import com.ravox.models.core.runtime.ActiveModel;
import com.ravox.models.core.runtime.ModelRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;

public class RavoxModelsPlugin extends JavaPlugin implements RavoxModelsApi {
    private ModelRuntime modelRuntime;
    private ImportService importService;
    private ResourcePackService resourcePackService;
    private LicenseService licenseService;
    private String keyPrefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.keyPrefix = getConfig().getString("namespace.prefix", "rvxmodels").toLowerCase(Locale.ROOT);
        this.modelRuntime = new ModelRuntime();
        this.importService = new ImportService(getDataFolder(), getConfig());
        this.resourcePackService = new ResourcePackService(this);
        this.licenseService = new LicenseService(this);

        licenseService.start();
        if (!licenseService.isValid()) {
            getLogger().severe("License invalid. Plugin will be disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        resourcePackService.start();
        if (getConfig().getBoolean("resourcepack.enabled", true)) {
            resourcePackService.buildPack();
        }

        Bukkit.getPluginManager().registerEvents(
                new ResourcePackListener(
                        resourcePackService,
                        getConfig().getBoolean("resourcepack.auto_apply_on_join", true),
                        resourcePackService.isForce()
                ),
                this
        );

        Bukkit.getServicesManager().register(RavoxModelsApi.class, this, this, ServicePriority.Normal);
        getLogger().info("RavoxModels " + getDescription().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregister(RavoxModelsApi.class, this);
        if (resourcePackService != null) {
            resourcePackService.stop();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ravoxmodels")) {
            return false;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help":
                sendHelp(sender);
                return true;
            case "import":
                if (args.length < 2) {
                    sender.sendMessage("Usage: /ravoxmodels import <filename.glb|filename.fbx>");
                    return true;
                }
                boolean queued = queueImport(args[1]);
                sender.sendMessage(queued
                        ? "Import accepted: " + args[1]
                        : "Import rejected (missing file or invalid extension): " + args[1]);
                return true;
            case "spawn":
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use spawn from command.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Usage: /ravoxmodels spawn <modelId>");
                    return true;
                }
                ModelHandle handle = spawnModel(args[1], player.getLocation());
                sender.sendMessage("Spawned model handle: " + handle.id());
                return true;
            case "play":
                if (args.length < 3) {
                    sender.sendMessage("Usage: /ravoxmodels play <handleUuid> <animationKey> [loop]");
                    return true;
                }
                UUID playId = parseUuid(args[1], sender);
                if (playId == null) {
                    return true;
                }
                boolean loop = args.length > 3 && Boolean.parseBoolean(args[3]);
                boolean playOk = playAnimation(new ModelHandle(playId), args[2], loop);
                sender.sendMessage(playOk ? "Animation started." : "Handle not found.");
                return true;
            case "transition":
                if (args.length < 5) {
                    sender.sendMessage("Usage: /ravoxmodels transition <handleUuid> <fromKey> <toKey> <blendMs> [loop]");
                    return true;
                }
                UUID transitionId = parseUuid(args[1], sender);
                if (transitionId == null) {
                    return true;
                }
                int blendMs;
                try {
                    blendMs = Integer.parseInt(args[4]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("blendMs must be an integer.");
                    return true;
                }
                boolean transitionLoop = args.length > 5 && Boolean.parseBoolean(args[5]);
                boolean transitionOk = transitionAnimation(new ModelHandle(transitionId), args[2], args[3], blendMs, transitionLoop);
                sender.sendMessage(transitionOk ? "Transition started." : "Handle not found.");
                return true;
            case "despawn":
                if (args.length < 2) {
                    sender.sendMessage("Usage: /ravoxmodels despawn <handleUuid>");
                    return true;
                }
                UUID despawnId = parseUuid(args[1], sender);
                if (despawnId == null) {
                    return true;
                }
                boolean despawnOk = despawn(new ModelHandle(despawnId));
                sender.sendMessage(despawnOk ? "Despawned." : "Handle not found.");
                return true;
            case "list":
                sender.sendMessage("Active models: " + modelRuntime.all().size());
                for (ActiveModel model : modelRuntime.all()) {
                    sender.sendMessage("- " + model.getHandle().id() + " model=" + model.getModelId() + " anim=" + model.getCurrentAnimation());
                }
                return true;
            case "pack":
                return handlePackCommand(sender, args);
            case "license":
                return handleLicenseCommand(sender, args);
            default:
                sender.sendMessage("Unknown subcommand.");
                return true;
        }
    }

    @Override
    public ModelHandle spawnModel(String modelId, Location location) {
        return modelRuntime.spawn(modelId, location);
    }

    @Override
    public boolean playAnimation(ModelHandle handle, String animationName, boolean loop) {
        return modelRuntime.play(handle, normalizeKey(animationName));
    }

    @Override
    public boolean transitionAnimation(ModelHandle handle, String fromAnimation, String toAnimation, int blendMillis, boolean loop) {
        String normalizedFrom = normalizeKey(fromAnimation);
        String normalizedTo = normalizeKey(toAnimation);
        getLogger().info("Transition " + handle.id() + " " + normalizedFrom + " -> " + normalizedTo + " blendMs=" + blendMillis + " loop=" + loop);
        return modelRuntime.transition(handle, normalizedTo);
    }

    @Override
    public boolean despawn(ModelHandle handle) {
        return modelRuntime.despawn(handle);
    }

    @Override
    public boolean queueImport(String filename) {
        return importService.queue(filename);
    }

    @Override
    public boolean forceResourcePack(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            return false;
        }
        return resourcePackService.applyToPlayer(player);
    }

    @Override
    public String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return keyPrefix + ".undefined";
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith(keyPrefix + ".")) {
            return lower;
        }
        return keyPrefix + "." + lower;
    }

    @Override
    public String getVersion() {
        return getDescription().getVersion();
    }

    private boolean handlePackCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /ravoxmodels pack <build|info|force>");
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "build":
                boolean buildOk = resourcePackService.buildPack();
                sender.sendMessage(buildOk ? "Resourcepack built." : "Resourcepack build failed.");
                return true;
            case "info":
                sender.sendMessage("Pack URL: " + resourcePackService.getActiveUrl());
                sender.sendMessage("Pack SHA1: " + resourcePackService.getActiveSha1Hex());
                return true;
            case "force":
                if (args.length < 3) {
                    sender.sendMessage("Usage: /ravoxmodels pack force <player|*>");
                    return true;
                }
                if ("*".equals(args[2])) {
                    int applied = resourcePackService.applyToAll();
                    sender.sendMessage("Pack sent to " + applied + " players.");
                    return true;
                }
                boolean forceOk = forceResourcePack(args[2]);
                sender.sendMessage(forceOk ? "Pack sent to " + args[2] : "Player not found or no active pack.");
                return true;
            default:
                sender.sendMessage("Unknown pack command.");
                return true;
        }
    }

    private boolean handleLicenseCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /ravoxmodels license <status|refresh>");
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status":
                sender.sendMessage(licenseService.statusLine());
                return true;
            case "refresh":
                boolean ok = licenseService.refreshNow();
                sender.sendMessage(ok ? "License refresh OK." : "License refresh failed.");
                return true;
            default:
                sender.sendMessage("Unknown license command.");
                return true;
        }
    }

    private static UUID parseUuid(String raw, CommandSender sender) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("Invalid UUID: " + raw);
            return null;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("RavoxModels v" + getDescription().getVersion());
        sender.sendMessage("/ravoxmodels import <filename>");
        sender.sendMessage("/ravoxmodels spawn <modelId>");
        sender.sendMessage("/ravoxmodels play <handleUuid> <animationKey> [loop]");
        sender.sendMessage("/ravoxmodels transition <handleUuid> <fromKey> <toKey> <blendMs> [loop]");
        sender.sendMessage("/ravoxmodels despawn <handleUuid>");
        sender.sendMessage("/ravoxmodels list");
        sender.sendMessage("/ravoxmodels pack <build|info|force>");
        sender.sendMessage("/ravoxmodels license <status|refresh>");
    }
}
