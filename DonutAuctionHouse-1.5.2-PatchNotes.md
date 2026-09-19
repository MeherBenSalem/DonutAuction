# DonutAuctionHouse v1.5.2

### Fixes
* Browse GUI previous-page control restored (slot 46). Sell held item stays at slot 45; next page stays at slot 53.
* After leaving page 1, players can go back without disconnecting.
* Page arrows show `page/totalPages` and the total listing count.
* Changing sort, filter, or search returns the browse session to page 1.
* Browse pagination clamps the page to at least 1.

### Compatibility
* Paper and Folia, Minecraft 1.20.1 through 26.3, Java 17.

### Upgrade Notes
1. Replace the jar. Keep existing `plugins/DonutAuctionHouse/` data.
2. Restart the server (or `/ah reload` after replacing the jar if you only need new `messages.yml` keys; a restart is still recommended).
