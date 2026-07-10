package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.model.PendingSaleTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;

/**
 * Thread-safe registry for pending sell transactions.
 * <p>
 * Ownership rule: an item exists in exactly one of player inventory, a pending
 * sale, or an active auction listing. {@link #claim} / {@link #claimByPlayer}
 * remove the transaction atomically so only one consumer can list or return it.
 */
public final class PendingSaleRegistry {

    public enum ClaimResultType {
        SUCCESS,
        NOT_FOUND,
        PLAYER_MISMATCH
    }

    public record ClaimResult(ClaimResultType type, PendingSaleTransaction transaction) {
        public boolean success() {
            return type == ClaimResultType.SUCCESS;
        }
    }

    private final Map<UUID, PendingSaleTransaction> byTransactionId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToTransaction = new ConcurrentHashMap<>();
    private final AtomicLong invalidActionLogCounter = new AtomicLong();
    private final Logger logger;
    private final boolean debugLogging;

    public PendingSaleRegistry(Logger logger, boolean debugLogging) {
        this.logger = logger;
        this.debugLogging = debugLogging;
    }

    /**
     * Registers a new pending sale for the player. If the player already has a
     * pending sale, the previous transaction is replaced and returned so the
     * caller can restore that item.
     */
    public BeginResult begin(UUID playerId, ItemStack item, double price) {
        if (playerId == null) {
            throw new NullPointerException("playerId");
        }
        if (item == null) {
            throw new NullPointerException("item");
        }

        UUID transactionId = UUID.randomUUID();
        PendingSaleTransaction transaction = new PendingSaleTransaction(
                transactionId,
                playerId,
                item,
                price,
                System.currentTimeMillis()
        );

        UUID previousId = playerToTransaction.put(playerId, transactionId);
        PendingSaleTransaction replaced = null;
        if (previousId != null) {
            replaced = byTransactionId.remove(previousId);
            if (replaced != null) {
                logInvalid("begin replaced existing pending sale for player " + playerId
                        + " (previousTx=" + previousId + ", newTx=" + transactionId + ")");
            }
        }
        byTransactionId.put(transactionId, transaction);
        return new BeginResult(transaction, replaced);
    }

    public record BeginResult(PendingSaleTransaction created, PendingSaleTransaction replaced) {
    }

    /**
     * Atomically claims a pending sale by transaction id and player ownership.
     * Returns SUCCESS at most once per transaction id.
     */
    public ClaimResult claim(UUID transactionId, UUID playerId) {
        if (transactionId == null || playerId == null) {
            return new ClaimResult(ClaimResultType.NOT_FOUND, null);
        }

        PendingSaleTransaction existing = byTransactionId.get(transactionId);
        if (existing == null) {
            logInvalid("claim repeated/missing tx=" + transactionId + " player=" + playerId);
            return new ClaimResult(ClaimResultType.NOT_FOUND, null);
        }
        if (!existing.playerId().equals(playerId)) {
            logInvalid("claim player mismatch tx=" + transactionId + " expected=" + existing.playerId()
                    + " actual=" + playerId);
            return new ClaimResult(ClaimResultType.PLAYER_MISMATCH, null);
        }

        // Atomic conditional remove: only the first successful remove owns the item.
        if (!byTransactionId.remove(transactionId, existing)) {
            logInvalid("claim race/repeated tx=" + transactionId + " player=" + playerId);
            return new ClaimResult(ClaimResultType.NOT_FOUND, null);
        }

        playerToTransaction.remove(playerId, transactionId);
        return new ClaimResult(ClaimResultType.SUCCESS, existing);
    }

    /**
     * Atomically claims whatever pending sale the player currently owns (if any).
     */
    public ClaimResult claimByPlayer(UUID playerId) {
        if (playerId == null) {
            return new ClaimResult(ClaimResultType.NOT_FOUND, null);
        }
        UUID transactionId = playerToTransaction.get(playerId);
        if (transactionId == null) {
            return new ClaimResult(ClaimResultType.NOT_FOUND, null);
        }
        return claim(transactionId, playerId);
    }

    public Optional<PendingSaleTransaction> peek(UUID transactionId) {
        return Optional.ofNullable(byTransactionId.get(transactionId));
    }

    public Optional<PendingSaleTransaction> peekByPlayer(UUID playerId) {
        UUID transactionId = playerToTransaction.get(playerId);
        if (transactionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byTransactionId.get(transactionId));
    }

    public boolean hasPending(UUID playerId) {
        return playerToTransaction.containsKey(playerId);
    }

    /**
     * Removes and returns all pending transactions (server shutdown recovery).
     */
    public List<PendingSaleTransaction> drainAll() {
        List<PendingSaleTransaction> drained = new ArrayList<>();
        for (UUID transactionId : new ArrayList<>(byTransactionId.keySet())) {
            PendingSaleTransaction removed = byTransactionId.remove(transactionId);
            if (removed != null) {
                playerToTransaction.remove(removed.playerId(), transactionId);
                drained.add(removed);
            }
        }
        return drained;
    }

    public int size() {
        return byTransactionId.size();
    }

    private void logInvalid(String message) {
        // Sample invalid/repeated actions so laggy double-clicks do not spam the console.
        long count = invalidActionLogCounter.incrementAndGet();
        if (debugLogging || count <= 5 || count % 50 == 0) {
            if (logger != null) {
                logger.fine("[PendingSale] invalid/repeated action (#" + count + "): " + message);
            }
        }
    }
}
