package com.ravox.models.core.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ModelRegistry {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type INDEX_TYPE = new TypeToken<IndexFile>() {
    }.getType();

    private final Path indexPath;
    private final Map<String, ModelDefinition> models = new LinkedHashMap<>();

    public ModelRegistry(Path indexPath) {
        this.indexPath = indexPath;
    }

    public synchronized void load() throws IOException {
        models.clear();
        if (!Files.exists(indexPath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            IndexFile parsed = GSON.fromJson(reader, INDEX_TYPE);
            if (parsed == null || parsed.models == null) {
                return;
            }
            for (ModelDefinition model : parsed.models) {
                models.put(model.getId(), model);
            }
        }
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(indexPath.getParent());
        Path temp = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        IndexFile out = new IndexFile(new ArrayList<>(models.values()));
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(out, writer);
        }
        try {
            Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized void upsert(ModelDefinition definition) {
        models.put(definition.getId(), definition);
    }

    public synchronized Optional<ModelDefinition> find(String id) {
        return Optional.ofNullable(models.get(id));
    }

    public synchronized Collection<ModelDefinition> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(models.values()));
    }

    public synchronized Set<String> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(models.keySet()));
    }

    public synchronized int size() {
        return models.size();
    }

    public synchronized Map<String, ModelDefinition> asMapCopy() {
        return new HashMap<>(models);
    }

    private static final class IndexFile {
        private List<ModelDefinition> models;

        private IndexFile() {
            this.models = new ArrayList<>();
        }

        private IndexFile(List<ModelDefinition> models) {
            this.models = models;
        }
    }
}
