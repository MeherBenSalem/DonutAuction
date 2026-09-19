package io.nightbeam.donutauction.model;

public record AuctionBrowseRequest(
        int page,
        AuctionSortMode sortMode,
        AuctionFilterCategory filterCategory,
        String searchTerm
) {

    public AuctionBrowseRequest {
        page = Math.max(1, page);
        sortMode = sortMode == null ? AuctionSortMode.RECENTLY_LISTED : sortMode;
        filterCategory = filterCategory == null ? AuctionFilterCategory.ALL : filterCategory;
        searchTerm = searchTerm == null ? "" : searchTerm.trim();
    }

    public AuctionBrowseRequest withPage(int nextPage) {
        return new AuctionBrowseRequest(nextPage, sortMode, filterCategory, searchTerm);
    }

    public AuctionBrowseRequest withSortMode(AuctionSortMode newSortMode) {
        return new AuctionBrowseRequest(1, newSortMode, filterCategory, searchTerm);
    }

    public AuctionBrowseRequest withFilter(AuctionFilterCategory newFilterCategory) {
        return new AuctionBrowseRequest(1, sortMode, newFilterCategory, searchTerm);
    }

    public AuctionBrowseRequest withSearch(String newSearchTerm) {
        return new AuctionBrowseRequest(1, sortMode, filterCategory, newSearchTerm);
    }
}