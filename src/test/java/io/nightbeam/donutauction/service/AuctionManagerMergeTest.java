package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AuctionManagerMergeTest {

    @Test
    void mergeRemotePrefersNewerUpdatedAt() {
        AuctionManager manager = new AuctionManager(45);
        UUID auctionId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        long now = 1_000L;

        AuctionListing localSold = listing(auctionId, seller, AuctionStatus.SOLD, now + 50);
        manager.upsert(localSold);

        AuctionListing staleActive = listing(auctionId, seller, AuctionStatus.ACTIVE, now);
        manager.mergeRemote(staleActive);
        assertEquals(AuctionStatus.SOLD, manager.findCached(auctionId).status());
        assertEquals(now + 50, manager.findCached(auctionId).updatedAt());

        AuctionListing newerExpired = listing(auctionId, seller, AuctionStatus.EXPIRED, now + 80);
        manager.mergeRemote(newerExpired);
        assertEquals(AuctionStatus.EXPIRED, manager.findCached(auctionId).status());
        assertEquals(now + 80, manager.findCached(auctionId).updatedAt());
        assertNotEquals(AuctionStatus.ACTIVE, manager.findCached(auctionId).status());
    }

    @Test
    void loadUpdatedSinceStyleMergeDropsSoldFromBrowse() {
        AuctionManager manager = new AuctionManager(45);
        UUID auctionId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        AuctionListing active = listing(auctionId, seller, AuctionStatus.ACTIVE, 10L);
        manager.upsert(active);
        assertEquals(1, manager.browse(new AuctionBrowseRequest(1, null, null, ""), 20L).listings().size());

        manager.mergeRemote(listing(auctionId, seller, AuctionStatus.SOLD, 30L));
        assertEquals(0, manager.browse(new AuctionBrowseRequest(1, null, null, ""), 40L).listings().size());
    }

    private static AuctionListing listing(UUID auctionId, UUID seller, AuctionStatus status, long updatedAt) {
        return new AuctionListing(
                auctionId,
                new ItemStack(Material.STONE, 1),
                seller,
                10.0D,
                1L,
                10_000L,
                status,
                status == AuctionStatus.SOLD ? UUID.randomUUID() : null,
                status == AuctionStatus.SOLD ? updatedAt : 0L,
                false,
                updatedAt
        );
    }
}
