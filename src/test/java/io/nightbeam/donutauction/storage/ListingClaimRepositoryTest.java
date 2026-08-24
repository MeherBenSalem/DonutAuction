package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingClaimRepositoryTest {

    @Test
    void secondClaimSoldFailsAndDoesNotRecordWithdraw() throws Exception {
        InMemoryAuctionRepository repository = new InMemoryAuctionRepository();
        UUID auctionId = UUID.randomUUID();
        AuctionListing listing = activeListing(auctionId);
        repository.put(listing);

        UUID firstBuyer = UUID.randomUUID();
        UUID secondBuyer = UUID.randomUUID();
        long now = System.currentTimeMillis();

        assertTrue(repository.claimSold(auctionId, firstBuyer, now).get(2, TimeUnit.SECONDS));
        boolean second = repository.claimSold(auctionId, secondBuyer, now + 1).get(2, TimeUnit.SECONDS);
        assertFalse(second);
        if (second) {
            repository.recordWithdrawAttempt();
        }
        assertEquals(0, repository.withdrawAttempts());
        assertEquals(AuctionStatus.SOLD, repository.get(auctionId).status());
        assertEquals(firstBuyer, repository.get(auctionId).buyer());
    }

    @Test
    void concurrentClaimSoldOnlyOneWins() throws Exception {
        InMemoryAuctionRepository repository = new InMemoryAuctionRepository();
        UUID auctionId = UUID.randomUUID();
        repository.put(activeListing(auctionId));

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID buyer = UUID.randomUUID();
            futures.add(pool.submit(() -> {
                start.await();
                if (Boolean.TRUE.equals(repository.claimSold(auctionId, buyer, System.currentTimeMillis()).get())) {
                    wins.incrementAndGet();
                    repository.recordWithdrawAttempt();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(1, wins.get());
        assertEquals(1, repository.withdrawAttempts());
        assertEquals(AuctionStatus.SOLD, repository.get(auctionId).status());
    }

    private static AuctionListing activeListing(UUID auctionId) {
        long now = System.currentTimeMillis();
        return new AuctionListing(
                auctionId,
                new ItemStack(Material.DIAMOND, 1),
                UUID.randomUUID(),
                100.0D,
                now,
                now + 3_600_000L,
                AuctionStatus.ACTIVE,
                null,
                0L,
                false,
                now
        );
    }
}
