package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.AuctionSortMode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuctionGuiPaginationTest {

    @Test
    void previousAndNextSlotsMirrorPlayerItemsAroundSellButton() {
        assertEquals(45, AuctionGui.SELL_HELD_SLOT);
        assertEquals(46, AuctionGui.PREVIOUS_PAGE_SLOT);
        assertEquals(53, AuctionGui.NEXT_PAGE_SLOT);
    }

    @Test
    void previousClickMovesBackWhenNotOnFirstPage() {
        AuctionBrowseRequest request = requestOnPage(2);
        AuctionBrowseRequest next = AuctionGui.pageAfterClick(request, page(2, 3), AuctionGui.PREVIOUS_PAGE_SLOT);
        assertEquals(1, next.page());
    }

    @Test
    void previousClickDoesNothingOnFirstPage() {
        AuctionBrowseRequest request = requestOnPage(1);
        AuctionBrowseRequest next = AuctionGui.pageAfterClick(request, page(1, 3), AuctionGui.PREVIOUS_PAGE_SLOT);
        assertEquals(request, next);
    }

    @Test
    void nextClickMovesForwardWhenAnotherPageExists() {
        AuctionBrowseRequest request = requestOnPage(1);
        AuctionBrowseRequest next = AuctionGui.pageAfterClick(request, page(1, 2), AuctionGui.NEXT_PAGE_SLOT);
        assertEquals(2, next.page());
    }

    @Test
    void nextClickDoesNothingOnLastPage() {
        AuctionBrowseRequest request = requestOnPage(2);
        AuctionBrowseRequest next = AuctionGui.pageAfterClick(request, page(2, 2), AuctionGui.NEXT_PAGE_SLOT);
        assertEquals(request, next);
    }

    @Test
    void sellSlotDoesNotChangePage() {
        AuctionBrowseRequest request = requestOnPage(2);
        AuctionBrowseRequest next = AuctionGui.pageAfterClick(request, page(2, 3), AuctionGui.SELL_HELD_SLOT);
        assertEquals(request, next);
    }

    private static AuctionBrowseRequest requestOnPage(int page) {
        return new AuctionBrowseRequest(page, AuctionSortMode.RECENTLY_LISTED, AuctionFilterCategory.ALL, "");
    }

    private static AuctionPage page(int currentPage, int totalPages) {
        return new AuctionPage(List.of(), currentPage, totalPages, 45L * totalPages);
    }
}
