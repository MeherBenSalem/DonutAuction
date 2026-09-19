package io.nightbeam.donutauction.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuctionBrowseRequestTest {

    @Test
    void constructorClampsNonPositivePageToOne() {
        assertEquals(1, new AuctionBrowseRequest(0, null, null, null).page());
        assertEquals(1, new AuctionBrowseRequest(-5, AuctionSortMode.LOWEST_PRICE, AuctionFilterCategory.TOOLS, "stone").page());
    }

    @Test
    void withPageClampsNonPositivePageToOne() {
        AuctionBrowseRequest request = new AuctionBrowseRequest(3, AuctionSortMode.LATEST, AuctionFilterCategory.ALL, "diamond");
        assertEquals(1, request.withPage(0).page());
        assertEquals(2, request.withPage(2).page());
        assertEquals(AuctionSortMode.LATEST, request.withPage(2).sortMode());
        assertEquals("diamond", request.withPage(2).searchTerm());
    }

    @Test
    void withSortModeResetsPageToOne() {
        AuctionBrowseRequest request = pageTwo();
        AuctionBrowseRequest next = request.withSortMode(AuctionSortMode.HIGHEST_PRICE);
        assertEquals(1, next.page());
        assertEquals(AuctionSortMode.HIGHEST_PRICE, next.sortMode());
        assertEquals(request.filterCategory(), next.filterCategory());
        assertEquals(request.searchTerm(), next.searchTerm());
    }

    @Test
    void withFilterResetsPageToOne() {
        AuctionBrowseRequest request = pageTwo();
        AuctionBrowseRequest next = request.withFilter(AuctionFilterCategory.BLOCKS);
        assertEquals(1, next.page());
        assertEquals(AuctionFilterCategory.BLOCKS, next.filterCategory());
        assertEquals(request.sortMode(), next.sortMode());
        assertEquals(request.searchTerm(), next.searchTerm());
    }

    @Test
    void withSearchResetsPageToOne() {
        AuctionBrowseRequest request = pageTwo();
        AuctionBrowseRequest next = request.withSearch("oak");
        assertEquals(1, next.page());
        assertEquals("oak", next.searchTerm());
        assertEquals(request.sortMode(), next.sortMode());
        assertEquals(request.filterCategory(), next.filterCategory());
    }

    private static AuctionBrowseRequest pageTwo() {
        return new AuctionBrowseRequest(2, AuctionSortMode.RECENTLY_LISTED, AuctionFilterCategory.ALL, "stone");
    }
}
