package com.ravox.models.bridge.customore;

import com.ravox.models.api.ModelHandle;
import com.ravox.models.api.RavoxModelsApi;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class CustomOreBridgePlugin extends JavaPlugin {
    private RavoxModelsApi api;
    private BossPhaseController phaseController;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<RavoxModelsApi> provider = Bukkit.getServicesManager().getRegistration(RavoxModelsApi.class);
        if (provider == null) {
            getLogger().warning("RavoxModels API service not found. Bridge stays idle.");
            return;
        }

        this.api = provider.getProvider();
        this.phaseController = new BossPhaseController(api, getConfig());
        getLogger().info("Connected to RavoxModels v" + api.getVersion());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rvxbridge")) {
            return false;
        }
        if (api == null || phaseController == null) {
            sender.sendMessage("RavoxModels API not connected.");
            return true;
        }
        if (args.length < 4 || !"phase".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Usage: /rvxbridge phase <handleUuid> <currentHp> <maxHp>");
            return true;
        }
        UUID id;
        try {
            id = UUID.fromString(args[1]);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("Invalid UUID: " + args[1]);
            return true;
        }
        double currentHp;
        double maxHp;
        try {
            currentHp = Double.parseDouble(args[2]);
            maxHp = Double.parseDouble(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("HP values must be numbers.");
            return true;
        }
        boolean changed = phaseController.updateBossPhase(new ModelHandle(id), currentHp, maxHp);
        sender.sendMessage(changed ? "Phase animation updated." : "No phase change.");
        return true;
    }
}
