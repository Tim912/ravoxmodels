package com.ravox.models.core;

import com.ravox.models.api.ModelHandle;
import com.ravox.models.api.RavoxModelsApi;
import com.ravox.models.core.converter.BundledToolsInstaller;
import com.ravox.models.core.importer.ImportRecord;
import com.ravox.models.core.importer.ImportService;
import com.ravox.models.core.license.LicenseService;
import com.ravox.models.core.model.ModelDefinition;
import com.ravox.models.core.resourcepack.ResourcePackListener;
import com.ravox.models.core.resourcepack.ResourcePackService;
import com.ravox.models.core.runtime.ActiveModel;
import com.ravox.models.core.runtime.ModelRuntime;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public class RavoxModelsPlugin extends JavaPlugin implements RavoxModelsApi, TabCompleter {
    private static final String PREFIX = ChatColor.DARK_AQUA + "[RavoxModels] " + ChatColor.RESET;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private ModelRuntime modelRuntime;
    private ImportService importService;
    private ResourcePackService resourcePackService;
    private LicenseService licenseService;
    private String keyPrefix;
    private boolean rebuildPackOnImport;
    private boolean autoForcePackOnImport;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.keyPrefix = getConfig().getString("namespace.prefix", "rvxmodels").toLowerCase(Locale.ROOT);
        this.rebuildPackOnImport = getConfig().getBoolean("import.rebuild_pack_on_success", true);
        this.autoForcePackOnImport = getConfig().getBoolean("import.force_pack_after_build", false);
        if (getConfig().getBoolean("tools.install_bundled_converter", true)) {
            new BundledToolsInstaller(this).install(getConfig().getBoolean("tools.overwrite_on_start", false));
        }

        try {
            this.importService = new ImportService(this, getConfig());
        } catch (IOException ex) {
            getLogger().severe("Could not start import service: " + ex.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.resourcePackService = new ResourcePackService(this);
        this.licenseService = new LicenseService(this);
        this.modelRuntime = new ModelRuntime(this, importService.getRegistry(), resourcePackService.getModelNamespace());

        licenseService.start();
        if (!licenseService.isValid()) {
            getLogger().severe("License invalid. Plugin will be disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        importService.setSuccessCallback(definition -> Bukkit.getScheduler().runTask(this, () -> handleSuccessfulImport(definition)));
        importService.start();
        modelRuntime.start();

        resourcePackService.start();
        if (getConfig().getBoolean("resourcepack.enabled", true)) {
            boolean built = resourcePackService.buildPack(importService.allModels());
            if (!built) {
                getLogger().warning("Initial resourcepack build failed.");
            }
        }

        Bukkit.getPluginManager().registerEvents(
                new ResourcePackListener(
                        this,
                        resourcePackService,
                        getConfig().getBoolean("resourcepack.auto_apply_on_join", true),
                        resourcePackService.isForce()
                ),
                this
        );

        PluginCommand command = getCommand("ravoxmodels");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        Bukkit.getServicesManager().register(RavoxModelsApi.class, this, this, ServicePriority.Normal);
        getLogger().info("RavoxModels " + getDescription().getVersion() + " enabled with " + importService.getRegistry().size() + " model(s).");
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregister(RavoxModelsApi.class, this);
        if (modelRuntime != null) {
            modelRuntime.stop();
        }
        if (importService != null) {
            importService.stop();
        }
        if (resourcePackService != null) {
            resourcePackService.stop();
        }
        if (licenseService != null) {
            licenseService.stop();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ravoxmodels")) {
            return false;
        }
        if (!sender.hasPermission("ravoxmodels.admin")) {
            msg(sender, "Missing permission: ravoxmodels.admin");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            case "status" -> handleStatusCommand(sender);
            case "import" -> handleImportCommand(sender, args);
            case "import-history" -> handleImportHistory(sender, args);
            case "models" -> handleModelsCommand(sender);
            case "model" -> handleModelCommand(sender, args);
            case "spawn" -> handleSpawnCommand(sender, args);
            case "play" -> handlePlayCommand(sender, args);
            case "transition" -> handleTransitionCommand(sender, args);
            case "state" -> handleStateCommand(sender, args);
            case "despawn" -> handleDespawnCommand(sender, args);
            case "kill" -> handleKillCommand(sender, args);
            case "list" -> handleListCommand(sender);
            case "pack" -> handlePackCommand(sender, args);
            case "license" -> handleLicenseCommand(sender, args);
            default -> {
                msg(sender, "Unknown subcommand. Use /ravoxmodels help");
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("ravoxmodels.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filterStartsWith(
                args[0],
                    List.of("help", "status", "import", "import-history", "models", "model", "spawn", "play", "transition", "state", "despawn", "kill", "list", "pack", "license")
            );
        }
        if (args.length == 2 && "import".equalsIgnoreCase(args[0])) {
            return importService.listImportCandidates(args[1], 40);
        }
        if (args.length == 2 && "pack".equalsIgnoreCase(args[0])) {
            return filterStartsWith(args[1], List.of("build", "info", "force", "diagnose"));
        }
        if (args.length == 3 && "pack".equalsIgnoreCase(args[0]) && "diagnose".equalsIgnoreCase(args[1])) {
            return filterStartsWith(args[2], listModelIds());
        }
        if (args.length == 2 && "license".equalsIgnoreCase(args[0])) {
            return filterStartsWith(args[1], List.of("status", "refresh"));
        }
        if (args.length == 2 && "model".equalsIgnoreCase(args[0])) {
            return filterStartsWith(args[1], List.of("info"));
        }
        if (args.length == 3 && "model".equalsIgnoreCase(args[0]) && "info".equalsIgnoreCase(args[1])) {
            return filterStartsWith(args[2], listModelIds());
        }
        if (args.length == 2 && ("spawn".equalsIgnoreCase(args[0]) || "model".equalsIgnoreCase(args[0]))) {
            return filterStartsWith(args[1], listModelIds());
        }
        if (args.length == 2 && ("play".equalsIgnoreCase(args[0]) || "transition".equalsIgnoreCase(args[0]) || "state".equalsIgnoreCase(args[0]) || "despawn".equalsIgnoreCase(args[0]))) {
            return filterStartsWith(args[1], activeHandleIds());
        }
        if (args.length == 2 && "kill".equalsIgnoreCase(args[0])) {
            List<String> candidates = new ArrayList<>();
            candidates.add("*");
            candidates.addAll(activeHandleIds());
            return filterStartsWith(args[1], candidates);
        }
        if (args.length == 3 && "pack".equalsIgnoreCase(args[0]) && "force".equalsIgnoreCase(args[1])) {
            List<String> players = new ArrayList<>();
            players.add("*");
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(player.getName());
            }
            return filterStartsWith(args[2], players);
        }
        return List.of();
    }

    @Override
    public ModelHandle spawnModel(String modelId, Location location) {
        if (modelId == null || location == null) {
            return null;
        }
        String normalizedModelId = modelId.toLowerCase(Locale.ROOT);
        return modelRuntime.spawn(normalizedModelId, location);
    }

    @Override
    public boolean playAnimation(ModelHandle handle, String animationName, boolean loop) {
        String normalized = normalizeKey(animationName);
        return modelRuntime.play(handle, normalized, loop);
    }

    @Override
    public boolean transitionAnimation(ModelHandle handle, String fromAnimation, String toAnimation, int blendMillis, boolean loop) {
        String from = normalizeKey(fromAnimation);
        String to = normalizeKey(toAnimation);
        return modelRuntime.transition(handle, from, to, blendMillis, loop);
    }

    @Override
    public boolean despawn(ModelHandle handle) {
        return modelRuntime.despawn(handle);
    }

    @Override
    public boolean setState(ModelHandle handle, String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        return modelRuntime.setState(handle, state.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean queueImport(String filename) {
        return importService.queue(filename);
    }

    @Override
    public boolean forceResourcePack(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);
        return player != null && resourcePackService.applyToPlayer(player);
    }

    @Override
    public boolean modelExists(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        return importService.getRegistry().find(modelId.toLowerCase(Locale.ROOT)).isPresent();
    }

    @Override
    public List<String> listModelIds() {
        List<String> out = new ArrayList<>(importService.getRegistry().ids());
        out.sort(String::compareTo);
        return out;
    }

    @Override
    public String getCurrentAnimation(ModelHandle handle) {
        ActiveModel model = modelRuntime.find(handle.id());
        return model == null ? null : model.getCurrentAnimation();
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

    private boolean handleStatusCommand(CommandSender sender) {
        int modelCount = importService.getRegistry().size();
        msg(sender, "RavoxModels v" + getVersion());
        msg(sender, "License: " + licenseService.statusLine());
        msg(sender, "ImportDir: " + importService.getImportDirectory());
        msg(sender, "Models: " + modelCount);
        msg(sender, "ActiveRuntimeModels: " + modelRuntime.all().size());
        msg(sender, "ImportQueue: " + importService.getQueuedJobs());
        msg(sender, "ConverterCommandEnabled: " + getConfig().getBoolean("converter.command.enabled", true));
        msg(sender, "BundledToolsDir: " + getDataFolder().toPath().resolve("tools"));
        msg(sender, "PackNamespace: " + resourcePackService.getModelNamespace());
        msg(sender, "PackURL: " + resourcePackService.getActiveUrl());
        msg(sender, "PackSHA1: " + resourcePackService.getActiveSha1Hex());
        msg(sender, "PackFile: " + resourcePackService.getActiveZipPath());
        msg(sender, "PackModelCount: " + modelCount);
        msg(sender, "PackLastBuild: " + formatEpoch(resourcePackService.getLastBuildAt()));
        return true;
    }

    private boolean handleImportCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /ravoxmodels import <filename.glb|filename.fbx>");
            return true;
        }
        boolean queued = queueImport(args[1]);
        msg(sender, queued
                ? "Import queued: " + args[1]
                : "Import rejected. Check file path/extension in " + importService.getImportDirectory());
        return true;
    }

    private boolean handleImportHistory(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        List<ImportRecord> history = importService.recentHistory(limit);
        if (history.isEmpty()) {
            msg(sender, "No import history.");
            return true;
        }
        msg(sender, "Recent imports:");
        for (ImportRecord record : history) {
            msg(sender, "- " + formatEpoch(record.getTimestampEpochMillis())
                    + " [" + record.getStatus() + "] "
                    + record.getSourceFile()
                    + (record.getModelId() == null ? "" : " -> " + record.getModelId())
                    + " (" + record.getMessage() + ")");
        }
        return true;
    }

    private boolean handleModelsCommand(CommandSender sender) {
        Collection<ModelDefinition> models = importService.allModels();
        if (models.isEmpty()) {
            msg(sender, "No imported models.");
            return true;
        }
        List<ModelDefinition> sorted = new ArrayList<>(models);
        sorted.sort(Comparator.comparing(ModelDefinition::getId));
        msg(sender, "Imported models (" + sorted.size() + "):");
        for (ModelDefinition model : sorted) {
            msg(sender, "- " + model.getId()
                    + " format=" + model.getFormat()
                    + " cmd=" + model.getCustomModelData()
                    + " anims=" + model.getAnimationCount()
                    + " tris=" + model.getEstimatedTriangles()
                    + " bones=" + model.getSkinBones()
                    + " converter=" + (model.isConverterApplied() ? model.getConverterName() : "none"));
        }
        return true;
    }

    private boolean handleModelCommand(CommandSender sender, String[] args) {
        if (args.length < 3 || !"info".equalsIgnoreCase(args[1])) {
            msg(sender, "Usage: /ravoxmodels model info <modelId>");
            return true;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        ModelDefinition model = importService.getRegistry().find(id).orElse(null);
        if (model == null) {
            msg(sender, "Model not found: " + id);
            return true;
        }
        msg(sender, "Model: " + model.getId());
        msg(sender, "Format: " + model.getFormat());
        msg(sender, "Source: " + model.getSourceFilename() + " sha1=" + model.getSourceSha1());
        msg(sender, "ImportedAt: " + formatEpoch(model.getImportedAtEpochMillis()));
        msg(sender, "Triangles: " + model.getEstimatedTriangles() + ", Bones: " + model.getSkinBones());
        msg(sender, "TextureMax: " + model.getMaxTextureSize() + ", Animations: " + model.getAnimationCount());
        msg(sender, "AnimationKeys: " + String.join(", ", model.getAnimationKeys()));
        msg(sender, "Material/CMD: " + model.getMaterialKey() + "/" + model.getCustomModelData());
        msg(sender, "Converter: " + (model.isConverterApplied() ? model.getConverterName() : "none"));
        msg(sender, "RuntimeArtifacts: " + String.join(", ", model.getRuntimeArtifacts()));
        msg(sender, "Warnings: " + String.join(" | ", model.getWarnings()));
        return true;
    }

    private boolean handleSpawnCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /ravoxmodels spawn <modelId> [player]");
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                msg(sender, "Player not found: " + args[2]);
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            msg(sender, "Console usage: /ravoxmodels spawn <modelId> <player>");
            return true;
        }
        ModelHandle handle = spawnModel(args[1], target.getLocation());
        if (handle == null) {
            msg(sender, "Spawn failed. Unknown model or invalid world.");
            return true;
        }
        msg(sender, "Spawned " + args[1] + " handle=" + handle.id());
        return true;
    }

    private boolean handlePlayCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg(sender, "Usage: /ravoxmodels play <handleUuid> <animationKey> [loop]");
            return true;
        }
        UUID id = parseUuid(args[1], sender);
        if (id == null) {
            return true;
        }
        boolean loop = args.length > 3 && Boolean.parseBoolean(args[3]);
        boolean ok = playAnimation(new ModelHandle(id), args[2], loop);
        msg(sender, ok ? "Animation playing." : "Unknown handle.");
        return true;
    }

    private boolean handleTransitionCommand(CommandSender sender, String[] args) {
        if (args.length < 5) {
            msg(sender, "Usage: /ravoxmodels transition <handleUuid> <fromKey> <toKey> <blendMs> [loop]");
            return true;
        }
        UUID id = parseUuid(args[1], sender);
        if (id == null) {
            return true;
        }
        int blend;
        try {
            blend = Integer.parseInt(args[4]);
        } catch (NumberFormatException ex) {
            msg(sender, "blendMs must be an integer.");
            return true;
        }
        boolean loop = args.length > 5 && Boolean.parseBoolean(args[5]);
        boolean ok = transitionAnimation(new ModelHandle(id), args[2], args[3], blend, loop);
        msg(sender, ok ? "Transition started." : "Unknown handle.");
        return true;
    }

    private boolean handleStateCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg(sender, "Usage: /ravoxmodels state <handleUuid> <state>");
            return true;
        }
        UUID id = parseUuid(args[1], sender);
        if (id == null) {
            return true;
        }
        boolean ok = setState(new ModelHandle(id), args[2]);
        msg(sender, ok ? "State updated." : "Unknown handle.");
        return true;
    }

    private boolean handleDespawnCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /ravoxmodels despawn <handleUuid>");
            return true;
        }
        UUID id = parseUuid(args[1], sender);
        if (id == null) {
            return true;
        }
        boolean ok = despawn(new ModelHandle(id));
        msg(sender, ok ? "Despawned." : "Unknown handle.");
        return true;
    }

    private boolean handleKillCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /ravoxmodels kill <handleUuid|*>");
            return true;
        }
        if ("*".equals(args[1])) {
            List<ActiveModel> active = new ArrayList<>(modelRuntime.all());
            int removed = 0;
            for (ActiveModel model : active) {
                if (despawn(model.getHandle())) {
                    removed++;
                }
            }
            msg(sender, "Killed " + removed + " model(s).");
            return true;
        }
        UUID id = parseUuid(args[1], sender);
        if (id == null) {
            return true;
        }
        boolean ok = despawn(new ModelHandle(id));
        msg(sender, ok ? "Killed." : "Unknown handle.");
        return true;
    }

    private boolean handleListCommand(CommandSender sender) {
        Collection<ActiveModel> active = modelRuntime.all();
        if (active.isEmpty()) {
            msg(sender, "No active runtime models.");
            return true;
        }
        msg(sender, "Active runtime models (" + active.size() + "):");
        for (ActiveModel model : active) {
            msg(sender, "- " + model.getHandle().id()
                    + " model=" + model.getModelId()
                    + " anim=" + model.getCurrentAnimation()
                    + " state=" + model.getState()
                    + (model.isTransitionActive() ? " transition=" + model.getTransitionFrom() + "->" + model.getTransitionTo() : ""));
        }
        return true;
    }

    private boolean handlePackCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /ravoxmodels pack <build|info|force>");
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "build" -> {
                int modelCount = importService.getRegistry().size();
                boolean built = resourcePackService.buildPack(importService.allModels());
                if (!built) {
                    msg(sender, "Resourcepack build failed.");
                    return true;
                }
                msg(sender, "Resourcepack built. models=" + modelCount);
                if (modelCount == 0) {
                    msg(sender, "Pack currently contains no model entries. Import at least one .glb/.fbx first.");
                }
                return true;
            }
            case "info" -> {
                msg(sender, "PackURL: " + resourcePackService.getActiveUrl());
                msg(sender, "PackSHA1: " + resourcePackService.getActiveSha1Hex());
                msg(sender, "PackFile: " + resourcePackService.getActiveZipPath());
                msg(sender, "PackModelCount: " + importService.getRegistry().size());
                msg(sender, "LastBuild: " + formatEpoch(resourcePackService.getLastBuildAt()));
                return true;
            }
            case "force" -> {
                if (args.length < 3) {
                    msg(sender, "Usage: /ravoxmodels pack force <player|*>");
                    return true;
                }
                if ("*".equals(args[2])) {
                    int count = resourcePackService.applyToAll();
                    msg(sender, "Pack sent to " + count + " player(s).");
                    return true;
                }
                boolean ok = forceResourcePack(args[2]);
                msg(sender, ok ? "Pack sent to " + args[2] : "Player not found or no active pack.");
                return true;
            }
            case "diagnose" -> {
                if (args.length < 3) {
                    msg(sender, "Usage: /ravoxmodels pack diagnose <modelId>");
                    return true;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                ModelDefinition model = importService.getRegistry().find(id).orElse(null);
                if (model == null) {
                    msg(sender, "Model not found: " + id);
                    return true;
                }
                msg(sender, "Pack diagnose:");
                for (String line : resourcePackService.diagnoseModel(model)) {
                    msg(sender, "- " + line);
                }
                return true;
            }
            default -> {
                msg(sender, "Unknown pack subcommand.");
                return true;
            }
        }
    }

    private boolean handleLicenseCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /ravoxmodels license <status|refresh>");
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                msg(sender, licenseService.statusLine());
                return true;
            }
            case "refresh" -> {
                boolean ok = licenseService.refreshNow();
                msg(sender, ok ? "License refresh successful." : "License refresh failed.");
                return true;
            }
            default -> {
                msg(sender, "Unknown license subcommand.");
                return true;
            }
        }
    }

    private void handleSuccessfulImport(ModelDefinition definition) {
        getLogger().info("Imported model " + definition.getId() + " (" + definition.getFormat() + ")");
        if (!rebuildPackOnImport) {
            return;
        }
        boolean built = resourcePackService.buildPack(importService.allModels());
        if (!built) {
            getLogger().warning("Resourcepack rebuild failed after importing " + definition.getId());
            return;
        }
        if (autoForcePackOnImport) {
            int sent = resourcePackService.applyToAll();
            getLogger().info("Resourcepack re-sent to " + sent + " player(s).");
        }
    }

    private void sendHelp(CommandSender sender) {
        msg(sender, "RavoxModels v" + getVersion());
        msg(sender, "/ravoxmodels help");
        msg(sender, "/ravoxmodels status");
        msg(sender, "/ravoxmodels import <filename>");
        msg(sender, "/ravoxmodels import-history [count]");
        msg(sender, "/ravoxmodels models");
        msg(sender, "/ravoxmodels model info <id>");
        msg(sender, "/ravoxmodels spawn <modelId> [player]");
        msg(sender, "/ravoxmodels play <handleUuid> <animationKey> [loop]");
        msg(sender, "/ravoxmodels transition <handleUuid> <fromKey> <toKey> <blendMs> [loop]");
        msg(sender, "/ravoxmodels state <handleUuid> <state>");
        msg(sender, "/ravoxmodels despawn <handleUuid>");
        msg(sender, "/ravoxmodels kill <handleUuid|*>");
        msg(sender, "/ravoxmodels list");
        msg(sender, "/ravoxmodels pack <build|info|force|diagnose>");
        msg(sender, "/ravoxmodels license <status|refresh>");
    }

    private static void msg(CommandSender sender, String raw) {
        if (raw == null) {
            return;
        }
        ChatColor tone = toneFor(raw);
        sender.sendMessage(PREFIX + tone + raw);
    }

    private static ChatColor toneFor(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("usage:")
                || normalized.startsWith("console usage:")
                || normalized.startsWith("/ravoxmodels")) {
            return ChatColor.YELLOW;
        }
        if (normalized.contains("missing permission")
                || normalized.contains("unknown")
                || normalized.contains("invalid")
                || normalized.contains("not found")
                || normalized.contains("failed")
                || normalized.contains("timed out")
                || normalized.contains("error")) {
            return ChatColor.RED;
        }
        if (normalized.contains("queued")
                || normalized.contains("successful")
                || normalized.startsWith("spawned")
                || normalized.startsWith("killed")
                || normalized.startsWith("despawned")
                || normalized.startsWith("state updated")
                || normalized.startsWith("animation playing")
                || normalized.startsWith("transition started")
                || normalized.startsWith("resourcepack built")
                || normalized.startsWith("pack sent")) {
            return ChatColor.GREEN;
        }
        if (normalized.startsWith("ravoxmodels v")
                || normalized.endsWith(":")
                || normalized.startsWith("imported models")
                || normalized.startsWith("active runtime models")
                || normalized.startsWith("recent imports")
                || normalized.startsWith("pack diagnose")) {
            return ChatColor.AQUA;
        }
        if (normalized.startsWith("- ")) {
            return ChatColor.GRAY;
        }
        return ChatColor.WHITE;
    }

    private static UUID parseUuid(String raw, CommandSender sender) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            msg(sender, "Invalid UUID: " + raw);
            return null;
        }
    }

    private static String formatEpoch(long epochMillis) {
        if (epochMillis <= 0L) {
            return "never";
        }
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private List<String> activeHandleIds() {
        List<String> out = new ArrayList<>();
        for (ActiveModel model : modelRuntime.all()) {
            out.add(model.getHandle().id().toString());
        }
        return out;
    }

    private static List<String> filterStartsWith(String input, List<String> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        String prefix = Objects.requireNonNullElse(input, "").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(value);
            }
        }
        Collections.sort(out);
        return out;
    }
}

