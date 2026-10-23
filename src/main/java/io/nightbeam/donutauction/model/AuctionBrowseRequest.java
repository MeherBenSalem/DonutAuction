package io.nightbeam.donutauction.model;

import java.util.UUID;

/**
 * What a player is looking at in the browser. {@code seller} narrows it to one player's listings
 * ({@code /ah view <player>}); {@code sellerName} is only for the menu title.
 */
public record AuctionBrowseRequest(
        int page,
        AuctionSortMode sortMode,
        AuctionFilterCategory filterCategory,
        String searchTerm,
        ListingFilter listingFilter,
        UUID seller,
        String sellerName
) {

    public AuctionBrowseRequest {
        page = Math.max(1, page);
        sortMode = sortMode == null ? AuctionSortMode.RECENTLY_LISTED : sortMode;
        filterCategory = filterCategory == null ? AuctionFilterCategory.ALL : filterCategory;
        searchTerm = searchTerm == null ? "" : searchTerm.trim();
        if (searchTerm.length() > 64) searchTerm = searchTerm.substring(0, 64);
        listingFilter = listingFilter == null ? ListingFilter.ALL : listingFilter;
    }

    public AuctionBrowseRequest(int page, AuctionSortMode sortMode, AuctionFilterCategory filterCategory, String searchTerm) {
        this(page, sortMode, filterCategory, searchTerm, ListingFilter.ALL, null, null);
    }

    public AuctionBrowseRequest withPage(int nextPage) {
        return new AuctionBrowseRequest(nextPage, sortMode, filterCategory, searchTerm, listingFilter, seller, sellerName);
    }

    public AuctionBrowseRequest withSortMode(AuctionSortMode newSortMode) {
        return new AuctionBrowseRequest(1, newSortMode, filterCategory, searchTerm, listingFilter, seller, sellerName);
    }

    public AuctionBrowseRequest withFilter(AuctionFilterCategory newFilterCategory) {
        return new AuctionBrowseRequest(1, sortMode, newFilterCategory, searchTerm, listingFilter, seller, sellerName);
    }

    public AuctionBrowseRequest withSearch(String newSearchTerm) {
        return new AuctionBrowseRequest(1, sortMode, filterCategory, newSearchTerm, listingFilter, seller, sellerName);
    }

    public AuctionBrowseRequest withListingFilter(ListingFilter newFilter) {
        return new AuctionBrowseRequest(1, sortMode, filterCategory, searchTerm, newFilter, seller, sellerName);
    }

    public AuctionBrowseRequest withSeller(UUID newSeller, String newSellerName) {
        return new AuctionBrowseRequest(1, sortMode, filterCategory, searchTerm, listingFilter, newSeller, newSellerName);
    }

    public boolean filtered() {
        return !searchTerm.isBlank() || filterCategory != AuctionFilterCategory.ALL || listingFilter != ListingFilter.ALL || seller != null;
    }
}
