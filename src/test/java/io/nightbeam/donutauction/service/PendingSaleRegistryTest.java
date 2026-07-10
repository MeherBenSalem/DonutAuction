package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.model.PendingSaleTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pending-sale transaction registry.
 * <p>
 * These cover the ownership invariants required by the /ah sell confirmation flow:
 * normal confirm, normal cancel, double cancel, GUI close, disconnect, full-inventory
 * recovery hand-off (claim once), and concurrent/laggy repeated callbacks.
 */
class PendingSaleRegistryTest {

    private PendingSaleRegistry registry;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        registry = new PendingSaleRegistry(Logger.getLogger("PendingSaleRegistryTest"), true);
        playerId = UUID.randomUUID();
    }

    private ItemStack diamondSword() {
        return new ItemStack(Material.DIAMOND_SWORD, 1);
    }

    private ItemStack stack(Material material, int amount) {
        return new ItemStack(material, amount);
    }

    @Test
    void normalConfirmationClaimsOnceAndRemovesPending() {
        PendingSaleTransaction tx = registry.begin(playerId, diamondSword(), 1.0D).created();

        PendingSaleRegistry.ClaimResult first = registry.claim(tx.transactionId(), playerId);
        assertTrue(first.success());
        assertEquals(Material.DIAMOND_SWORD, first.transaction().item().getType());
        assertEquals(0, registry.size());

        // Second confirm / concurrent confirm is a no-op.
        PendingSaleRegistry.ClaimResult second = registry.claim(tx.transactionId(), playerId);
        assertFalse(second.success());
        assertEquals(PendingSaleRegistry.ClaimResultType.NOT_FOUND, second.type());
    }

    @Test
    void normalCancellationClaimsOnceAndReturnsOwnership() {
        PendingSaleTransaction tx = registry.begin(playerId, stack(Material.GOLD_INGOT, 16), 25.0D).created();

        PendingSaleRegistry.ClaimResult cancel = registry.claim(tx.transactionId(), playerId);
        assertTrue(cancel.success());
        assertEquals(16, cancel.transaction().item().getAmount());
        assertEquals(0, registry.size());
    }

    @Test
    void doubleClickCancelOnlySucceedsOnce() {
        PendingSaleTransaction tx = registry.begin(playerId, diamondSword(), 1.0D).created();

        PendingSaleRegistry.ClaimResult first = registry.claim(tx.transactionId(), playerId);
        PendingSaleRegistry.ClaimResult second = registry.claim(tx.transactionId(), playerId);
        PendingSaleRegistry.ClaimResult third = registry.claimByPlayer(playerId);

        assertTrue(first.success());
        assertFalse(second.success());
        assertFalse(third.success());
        assertEquals(0, registry.size());
    }

    @Test
    void closingGuiClaimsByTransactionIdIdempotently() {
        PendingSaleTransaction tx = registry.begin(playerId, stack(Material.EMERALD, 3), 50.0D).created();

        // Close path
        PendingSaleRegistry.ClaimResult closeClaim = registry.claim(tx.transactionId(), playerId);
        assertTrue(closeClaim.success());

        // Laggy second close / cancel after already settled
        assertFalse(registry.claim(tx.transactionId(), playerId).success());
        assertFalse(registry.claimByPlayer(playerId).success());
    }

    @Test
    void disconnectSettlesViaClaimByPlayer() {
        PendingSaleTransaction tx = registry.begin(playerId, diamondSword(), 1.0D).created();
        assertTrue(registry.hasPending(playerId));

        PendingSaleRegistry.ClaimResult quitClaim = registry.claimByPlayer(playerId);
        assertTrue(quitClaim.success());
        assertEquals(tx.transactionId(), quitClaim.transaction().transactionId());
        assertFalse(registry.hasPending(playerId));

        // Reconnect / second quit callback cannot return the item again
        assertFalse(registry.claimByPlayer(playerId).success());
    }

    @Test
    void fullInventoryPathStillClaimsOnceForDropRecovery() {
        // Registry only owns the claim; restore/drop is caller's job after a single successful claim.
        PendingSaleTransaction tx = registry.begin(playerId, stack(Material.DIAMOND_BLOCK, 64), 100.0D).created();

        PendingSaleRegistry.ClaimResult claim = registry.claim(tx.transactionId(), playerId);
        assertTrue(claim.success());
        assertNotNull(claim.transaction().item());
        assertEquals(64, claim.transaction().item().getAmount());

        // Simulated laggy cancel + close + quit all after recovery
        assertFalse(registry.claim(tx.transactionId(), playerId).success());
        assertFalse(registry.claimByPlayer(playerId).success());
    }

    @Test
    void concurrentClaimsUnderLagOnlyOneSucceeds() throws Exception {
        PendingSaleTransaction tx = registry.begin(playerId, diamondSword(), 1.0D).created();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (registry.claim(tx.transactionId(), playerId).success()) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(1, successes.get(), "Exactly one concurrent claim must succeed");
        assertEquals(0, registry.size());
    }

    @Test
    void confirmAndCancelRaceOnlyOneWins() throws Exception {
        PendingSaleTransaction tx = registry.begin(playerId, diamondSword(), 1.0D).created();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        Future<?> confirm = pool.submit(() -> {
            try {
                start.await();
                if (registry.claim(tx.transactionId(), playerId).success()) {
                    successes.incrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        Future<?> cancel = pool.submit(() -> {
            try {
                start.await();
                if (registry.claim(tx.transactionId(), playerId).success()) {
                    successes.incrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        start.countDown();
        confirm.get(5, TimeUnit.SECONDS);
        cancel.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, successes.get());
        assertEquals(0, registry.size());
    }

    @Test
    void reOpenDoesNotConsumeTransactionUntilExplicitClaim() {
        // Simulates duration/category re-render: pending stays until cancel/confirm/close settle.
        PendingSaleTransaction tx = registry.begin(playerId, diamondSword(), 1.0D).created();
        assertTrue(registry.peek(tx.transactionId()).isPresent());
        assertTrue(registry.peekByPlayer(playerId).isPresent());
        assertEquals(1, registry.size());

        // Still claimable after "re-open"
        assertTrue(registry.claim(tx.transactionId(), playerId).success());
    }

    @Test
    void playerMismatchDoesNotConsumeTransaction() {
        PendingSaleTransaction tx = registry.begin(playerId, diamondSword(), 1.0D).created();
        UUID otherPlayer = UUID.randomUUID();

        PendingSaleRegistry.ClaimResult mismatch = registry.claim(tx.transactionId(), otherPlayer);
        assertEquals(PendingSaleRegistry.ClaimResultType.PLAYER_MISMATCH, mismatch.type());
        assertTrue(registry.peek(tx.transactionId()).isPresent());

        assertTrue(registry.claim(tx.transactionId(), playerId).success());
    }

    @Test
    void drainAllOnShutdownReturnsEachPendingOnce() {
        UUID player2 = UUID.randomUUID();
        registry.begin(playerId, diamondSword(), 1.0D);
        registry.begin(player2, stack(Material.APPLE, 5), 2.0D);

        List<PendingSaleTransaction> drained = registry.drainAll();
        assertEquals(2, drained.size());
        assertEquals(0, registry.size());
        assertTrue(registry.drainAll().isEmpty());
    }

    @Test
    void itemMetadataAmountAndTypePreservedThroughClone() {
        ItemStack original = stack(Material.ENCHANTED_GOLDEN_APPLE, 7);
        PendingSaleTransaction tx = registry.begin(playerId, original, 99.0D).created();

        // Mutating the caller's stack must not affect the registry copy.
        original.setAmount(1);

        PendingSaleRegistry.ClaimResult claim = registry.claim(tx.transactionId(), playerId);
        assertTrue(claim.success());
        ItemStack returned = claim.transaction().item();
        assertEquals(Material.ENCHANTED_GOLDEN_APPLE, returned.getType());
        assertEquals(7, returned.getAmount());
        assertEquals(99.0D, claim.transaction().price());
    }

    @Test
    void beginReplacesPreviousPendingAndReturnsItForRestore() {
        PendingSaleTransaction first = registry.begin(playerId, diamondSword(), 1.0D).created();
        PendingSaleRegistry.BeginResult secondBegin = registry.begin(playerId, stack(Material.STONE, 32), 5.0D);

        assertNotNull(secondBegin.replaced());
        assertEquals(first.transactionId(), secondBegin.replaced().transactionId());
        assertEquals(Material.DIAMOND_SWORD, secondBegin.replaced().item().getType());
        assertEquals(1, registry.size());
        assertFalse(registry.claim(first.transactionId(), playerId).success());
        assertTrue(registry.claim(secondBegin.created().transactionId(), playerId).success());
    }
}
