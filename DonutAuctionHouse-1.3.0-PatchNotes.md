# DonutAuctionHouse v1.3.0

### New Features
* **Sell from auction GUI** — emerald "Sell Held Item" button on the main auction house (slot 45) lists the item in your main hand
* **Adjustable sell price** — use +/- buttons in the sell GUI before confirming a listing

### Improvements
* Sell GUI enlarged to 45 slots with confirm/cancel on the bottom row (slots 40 and 44)
* Confirm purchase GUI enlarged to 45 slots with a gray border, centered item preview, and buyer balance display
* Sell flow logic shared between `/ah sell` and the auction GUI button
* Maven publish workflow supports `target/` jars with Paper/Folia/Bukkit loader tags

### Bug Fixes
* Fixed sell GUI confirm/cancel buttons overlapping duration and category controls
* Fixed item duplication when closing and reopening the sell menu during duration/category/price changes
* Pending sale is no longer cancelled when the sell GUI refreshes itself

### Credits
* **@LeoArs06** — SellGui Confirm/Cancel slot-collision fix and sell-menu close/reopen handling ([PR #4](https://github.com/MeherBenSalem/DonutAuction/pull/4)). Sorry this was shipped in 1.3.0 without merging/crediting your PR at the time.

### Configuration
* None

### Compatibility
* Drop-in update from 1.2.2
* Requires Vault **or** VaultUnlocked plus an economy plugin
* Folia-supported remains enabled
* No database migration required
* No permission changes

### Upgrade Notes
1. Install `DonutAuctionHouse-1.3.0.jar` (replace 1.2.2)
2. Restart the server
3. Open `/ah` and use the emerald button or `/ah sell <price>` to list items from your hand
