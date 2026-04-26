package com.ravox.models.core;

import com.ravox.models.api.ModelHandle;
import com.ravox.models.api.RavoxModelsApi;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class RavoxModelsPlugin extends JavaPlugin implements RavoxModelsApi {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getServicesManager().register(RavoxModelsApi.class, this, this, ServicePriority.Normal);
        getLogger().info("RavoxModels " + getDescription().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregister(RavoxModelsApi.class, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ravoxmodels")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage("RavoxModels v" + getDescription().getVersion());
            sender.sendMessage("Usage: /ravoxmodels import <filename.glb|filename.fbx>");
            return true;
        }

        if ("import".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                sender.sendMessage("Missing filename. Example: /ravoxmodels import boss.glb");
                return true;
            }
            sender.sendMessage("Import queued (stub): " + args[1]);
            return true;
        }

        sender.sendMessage("Unknown subcommand.");
        return true;
    }

    @Override
    public ModelHandle spawnModel(String modelId, Location location) {
        return new ModelHandle(UUID.randomUUID());
    }

    @Override
    public boolean playAnimation(ModelHandle handle, String animationName, boolean loop) {
        return true;
    }

    @Override
    public boolean despawn(ModelHandle handle) {
        return true;
    }

    @Override
    public String getVersion() {
        return getDescription().getVersion();
    }
}
