package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.util.ShulkerBoxSupport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The in-memory market: live rows only (active listings, unreclaimed items and items waiting for their
 * buyer). Settled rows are evicted, so memory stays proportional to what is for sale, not to history.
 */
public final class AuctionManager {

    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final Map<UUID, AuctionListing> listingCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> searchIndex = new ConcurrentHashMap<>();
    private final Map<String, AuctionPage> pageCache = new ConcurrentHashMap<>();
    /**
     * When settled rows were evicted, keyed by auction ID. A stale snapshot from another server (an older
     * "ACTIVE" row arriving late) must not bring a sold listing back. Kept for ten minutes.
     */
    private final Map<UUID, Long> tombstones = new ConcurrentHashMap<>();
    private static final long TOMBSTONE_MILLIS = 10 * 60_000L;
    private final int pageSize;

    public AuctionManager(int pageSize) {
        this.pageSize = Math.min(45, Math.max(1, pageSize));
    }

    public void replaceAll(Collection<AuctionListing> listings) {
        listingCache.clear();
        searchIndex.clear();
        listings.forEach(this::upsert);
        invalidatePages();
    }

    /** Stores a live row, or evicts it once it is settled. */
    public void upsert(AuctionListing listing) {
        if (!listing.isLive()) {
            tombstones.put(listing.auctionId(), listing.updatedAt());
            remove(listing.auctionId());
            if (tombstones.size() > 4096) pruneTombstones();
            return;
        }
        tombstones.remove(listing.auctionId());
        listingCache.put(listing.auctionId(), listing);
        searchIndex.put(listing.auctionId(), computeSearchText(listing));
        invalidatePages();
    }

    /**
     * Applies a remote MySQL/Redis snapshot. Ignores stale payloads whose {@code updatedAt}
     * is older than the listing already in this node's cache.
     *
     * @return the listing this node had before, or null; callers use it to notice sales and outbids
     */
    public AuctionListing mergeRemote(AuctionListing listing) {
        AuctionListing existing = listingCache.get(listing.auctionId());
        if (existing != null && existing.updatedAt() > listing.updatedAt()) {
            return null;
        }
        Long evicted = tombstones.get(listing.auctionId());
        if (existing == null && evicted != null && evicted > listing.updatedAt()) {
            return null;
        }
        upsert(listing);
        return existing;
    }

    private void pruneTombstones() {
        long cutoff = System.currentTimeMillis() - TOMBSTONE_MILLIS;
        tombstones.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    public void remove(UUID auctionId) {
        if (listingCache.remove(auctionId) != null) {
            invalidatePages();
        }
        searchIndex.remove(auctionId);
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

        String term = request.searchTerm().toLowerCase(Locale.ROOT);
        List<AuctionListing> filtered = listingCache.values().stream()
                .filter(listing -> listing.isActive(now))
                .filter(listing -> request.seller() == null || listing.seller().equals(request.seller()))
                .filter(listing -> request.listingFilter().matches(listing))
                .filter(listing -> matchesCategory(listing, request.filterCategory()))
                .filter(listing -> matchesSearch(listing, term))
                .sorted(request.sortMode().comparator().thenComparing(Comparator.comparing(AuctionListing::auctionId)))
                .collect(Collectors.toCollection(ArrayList::new));

        int totalResults = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalResults / (double) pageSize));
        int page = Math.max(1, Math.min(request.page(), totalPages));
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(filtered.size(), fromIndex + pageSize);
        List<AuctionListing> pageListings = fromIndex >= filtered.size() ? List.of() : List.copyOf(filtered.subList(fromIndex, toIndex));

        AuctionPage pageResult = new AuctionPage(pageListings, page, totalPages, totalResults);
        pageCache.put(cacheKey, pageResult);
        return pageResult;
    }

    /** A seller's active listings and unreclaimed items, newest first. */
    public List<AuctionListing> sellerListings(UUID sellerId) {
        return listingCache.values().stream()
                .filter(listing -> listing.seller().equals(sellerId))
                .filter(listing -> listing.status() == AuctionStatus.ACTIVE || listing.isReclaimable())
                .sorted(Comparator.comparingLong(AuctionListing::listingTime).reversed())
                .toList();
    }

    /** Items a player bought or won that still wait in their collection. */
    public List<AuctionListing> buyerCollection(UUID buyerId) {
        return listingCache.values().stream()
                .filter(listing -> listing.awaitsBuyer() && buyerId.equals(listing.buyer()))
                .sorted(Comparator.comparingLong(AuctionListing::soldTime).reversed())
                .toList();
    }

    public List<AuctionListing> activeListings(long now) {
        return listingCache.values().stream().filter(listing -> listing.isActive(now)).toList();
    }

    /** Active rows, including ones past their end time that the expiry scan has not reached yet. */
    public List<AuctionListing> allActive() {
        return listingCache.values().stream().filter(listing -> listing.status() == AuctionStatus.ACTIVE).toList();
    }

    public int size() {
        return listingCache.size();
    }

    public void invalidatePages() {
        pageCache.clear();
    }

    private boolean matchesCategory(AuctionListing listing, AuctionFilterCategory filterCategory) {
        return filterCategory.matches(listing.item());
    }

    private boolean matchesSearch(AuctionListing listing, String term) {
        if (term == null || term.isBlank()) {
            return true;
        }
        String text = searchIndex.computeIfAbsent(listing.auctionId(), ignored -> computeSearchText(listing));
        for (String word : term.split("\\s+")) {
            if (!word.isEmpty() && !text.contains(word)) return false;
        }
        return true;
    }

    /**
     * Everything a search can match: material and display name, lore, enchantments (stored books too),
     * the seller's name and the contents of shulker boxes. Every word of a query must match.
     */
    static String computeSearchText(AuctionListing listing) {
        StringBuilder text = new StringBuilder();
        appendItem(text, listing.item(), true);
        String seller = listing.sellerName();
        if (seller == null) {
            // Listings from 1.x have no stored name: use the server's name cache.
            try {
                seller = org.bukkit.Bukkit.getOfflinePlayer(listing.seller()).getName();
            } catch (RuntimeException | LinkageError unavailable) {
                seller = null;
            }
        }
        if (seller != null) text.append(' ').append(seller);
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void appendItem(StringBuilder text, ItemStack item, boolean includeContents) {
        text.append(' ').append(item.getType().name().replace('_', ' '));
        try {
            if (!item.hasItemMeta()) return;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            if (meta.hasDisplayName()) text.append(' ').append(PLAIN_TEXT.serialize(meta.displayName()));
            List<Component> lore = meta.hasLore() ? meta.lore() : null;
            if (lore != null) for (Component line : lore) text.append(' ').append(PLAIN_TEXT.serialize(line));
            meta.getEnchants().keySet().forEach(enchantment -> text.append(' ').append(enchantmentName(enchantment)));
            if (meta instanceof EnchantmentStorageMeta stored) {
                stored.getStoredEnchants().keySet().forEach(enchantment -> text.append(' ').append(enchantmentName(enchantment)));
            }
            if (includeContents && ShulkerBoxSupport.isShulkerBox(item)) {
                for (ItemStack content : ShulkerBoxSupport.getContents(item)) appendItem(text, content, false);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Tests and early boot have no Bukkit server; the material name is still searchable.
        }
    }

    private static String enchantmentName(Enchantment enchantment) {
        try {
            return enchantment.getKey().getKey().replace('_', ' ');
        } catch (RuntimeException | LinkageError unavailable) {
            return String.valueOf(enchantment).toLowerCase(Locale.ROOT);
        }
    }

    private String cacheKey(AuctionBrowseRequest request, long bucket) {
        return request.page() + ":" + request.sortMode().name() + ":" + request.filterCategory().name() + ":"
                + request.listingFilter().name() + ":" + request.seller() + ":"
                + request.searchTerm().toLowerCase(Locale.ROOT) + ":" + bucket;
    }
}
