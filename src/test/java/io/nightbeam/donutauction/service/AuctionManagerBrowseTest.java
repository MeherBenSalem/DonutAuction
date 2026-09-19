package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionManagerBrowseTest {

    @Test
    void browsePaginatesAndExposesPreviousNext() {
        AuctionManager manager = managerWithListings(5);
        long now = 50L;

        AuctionPage page1 = manager.browse(request(1), now);
        assertEquals(1, page1.currentPage());
        assertEquals(3, page1.totalPages());
        assertEquals(5, page1.totalResults());
        assertEquals(2, page1.listings().size());
        assertFalse(page1.hasPreviousPage());
        assertTrue(page1.hasNextPage());

        AuctionPage page2 = manager.browse(request(2), now);
        assertEquals(2, page2.currentPage());
        assertEquals(2, page2.listings().size());
        assertTrue(page2.hasPreviousPage());
        assertTrue(page2.hasNextPage());

        AuctionPage page3 = manager.browse(request(3), now);
        assertEquals(3, page3.currentPage());
        assertEquals(1, page3.listings().size());
        assertTrue(page3.hasPreviousPage());
        assertFalse(page3.hasNextPage());
    }

    @Test
    void browseClampsPageBelowOneToFirstPage() {
        AuctionManager manager = managerWithListings(5);
        AuctionPage page = manager.browse(request(0), 50L);
        assertEquals(1, page.currentPage());
        assertFalse(page.hasPreviousPage());
        assertEquals(2, page.listings().size());
    }

    @Test
    void browseClampsPageAboveTotalToLastPage() {
        AuctionManager manager = managerWithListings(5);
        AuctionPage page = manager.browse(request(99), 50L);
        assertEquals(3, page.currentPage());
        assertFalse(page.hasNextPage());
        assertEquals(1, page.listings().size());
    }

    private static AuctionManager managerWithListings(int count) {
        AuctionManager manager = new AuctionManager(2);
        UUID seller = UUID.randomUUID();
        for (int i = 0; i < count; i++) {
            manager.upsert(listing(seller, 100L + i));
        }
        return manager;
    }

    private static AuctionBrowseRequest request(int page) {
        return new AuctionBrowseRequest(page, null, null, "");
    }

    private static AuctionListing listing(UUID seller, long listingTime) {
        return new AuctionListing(
                UUID.randomUUID(),
                new ItemStack(Material.STONE, 1),
                seller,
                10.0D,
                listingTime,
                10_000L,
                AuctionStatus.ACTIVE,
                null,
                0L,
                false,
                listingTime
        );
    }
}
