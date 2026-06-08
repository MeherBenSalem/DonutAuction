package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.PlayerPreference;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerPreferenceRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<PlayerPreference> load(UUID playerId);

    CompletableFuture<Void> save(PlayerPreference preference);
}
