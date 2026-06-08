package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.storage.PlayerPreferenceRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerPreferenceManager {

    private final PlayerPreferenceRepository repository;
    private final Map<UUID, PlayerPreference> cache = new ConcurrentHashMap<>();

    public PlayerPreferenceManager(PlayerPreferenceRepository repository) {
        this.repository = repository;
    }

    public CompletableFuture<PlayerPreference> get(UUID playerId) {
        PlayerPreference cached = cache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return repository.load(playerId).thenApply(pref -> {
            cache.put(playerId, pref);
            return pref;
        });
    }

    public PlayerPreference getCached(UUID playerId) {
        return cache.get(playerId);
    }

    public void put(PlayerPreference preference) {
        cache.put(preference.playerId(), preference);
    }

    public CompletableFuture<Void> save(PlayerPreference preference) {
        cache.put(preference.playerId(), preference);
        return repository.save(preference);
    }

    public void unload(UUID playerId) {
        PlayerPreference pref = cache.remove(playerId);
        if (pref != null) {
            repository.save(pref);
        }
    }

    public void saveAll() {
        for (PlayerPreference pref : cache.values()) {
            repository.save(pref);
        }
    }
}
