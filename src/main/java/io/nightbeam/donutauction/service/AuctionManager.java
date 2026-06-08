package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class AuctionManager {

    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final Map<UUID, AuctionListing> listingCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> plainNameCache = new ConcurrentHashMap<>();
    private final Map<String, AuctionPage> pageCache = new ConcurrentHashMap<>();
    private final int pageSize;

    public AuctionManager(int pageSize) {
        this.pageSize = Math.min(45, Math.max(1, pageSize));
    }

    public void replaceAll(Collection<AuctionListing> listings) {
        listingCache.clear();
        plainNameCache.clear();
        listings.forEach(listing -> {
            listingCache.put(listing.auctionId(), listing);
            plainNameCache.put(listing.auctionId(), computePlainName(listing));
        });
        invalidatePages();
    }

    public void upsert(AuctionListing listing) {
        listingCache.put(listing.auctionId(), listing);
        plainNameCache.put(listing.auctionId(), computePlainName(listing));
        invalidatePages();
    }

    public void remove(UUID auctionId) {
        listingCache.remove(auctionId);
        plainNameCache.remove(auctionId);
        invalidatePages();
    }

    public AuctionListing findCached(UUID auctionId) {
        return listingCache.get(auctionId);
    }

    public AuctionPage browse(AuctionBrowseRequest request, long now) {
        String cacheKey = cacheKey(request, now / 5_000L);
        AuctionPage cached = pageCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<AuctionListing> filtered = listingCache.values().stream()
                .filter(listing -> listing.status() == AuctionStatus.ACTIVE)
                .filter(listing -> listing.expirationTime() > now)
                .filter(listing -> matchesCategory(listing, request.filterCategory()))
                .filter(listing -> matchesSearch(listing, request.searchTerm()))
                .sorted(request.sortMode().comparator().thenComparing(Comparator.comparing(AuctionListing::auctionId)))
                .collect(Collectors.toCollection(ArrayList::new));

        int totalResults = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalResults / (double) pageSize));
        int page = Math.min(request.page(), totalPages);
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(filtered.size(), fromIndex + pageSize);
        List<AuctionListing> pageListings = fromIndex >= filtered.size() ? List.of() : List.copyOf(filtered.subList(fromIndex, toIndex));

        AuctionPage pageResult = new AuctionPage(pageListings, page, totalPages, totalResults);
        pageCache.put(cacheKey, pageResult);
        return pageResult;
    }

    public List<AuctionListing> sellerListings(UUID sellerId) {
        return listingCache.values().stream()
                .filter(listing -> listing.seller().equals(sellerId))
                .sorted(Comparator.comparingLong(AuctionListing::listingTime).reversed())
                .toList();
    }

    public void invalidatePages() {
        pageCache.clear();
    }

    private boolean matchesCategory(AuctionListing listing, AuctionFilterCategory filterCategory) {
        return filterCategory.matches(listing.item());
    }

    private boolean matchesSearch(AuctionListing listing, String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return true;
        }
        String itemName = plainNameCache.getOrDefault(listing.auctionId(), computePlainName(listing));
        return itemName.toLowerCase(Locale.ENGLISH).contains(searchTerm.toLowerCase(Locale.ENGLISH));
    }

    private String computePlainName(AuctionListing listing) {
        if (listing.item().hasItemMeta() && listing.item().getItemMeta().hasDisplayName()) {
            return PLAIN_TEXT.serialize(listing.item().getItemMeta().displayName());
        }
        return listing.item().getType().name().replace('_', ' ');
    }

    private String cacheKey(AuctionBrowseRequest request, long bucket) {
        return request.page() + ":" + request.sortMode().name() + ":" + request.filterCategory().name() + ":"
                + request.searchTerm().toLowerCase(Locale.ENGLISH) + ":" + bucket;
    }
}
