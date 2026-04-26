package com.ravox.models.bridge.customore;

import com.ravox.models.api.RavoxModelsApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class CustomOreBridgePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        RegisteredServiceProvider<RavoxModelsApi> provider =
                Bukkit.getServicesManager().getRegistration(RavoxModelsApi.class);

        if (provider == null) {
            getLogger().warning("RavoxModels API service not found. Bridge stays idle.");
            return;
        }

        RavoxModelsApi api = provider.getProvider();
        getLogger().info("Connected to RavoxModels v" + api.getVersion());
    }
}
