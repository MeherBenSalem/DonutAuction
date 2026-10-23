# DonutAuctionHouse v2.0.0 — The Market Update

## New

- **Auctions with bids**, next to Buy It Now. Start one from the sell menu or with `/ah auction <starting bid>`. Each bid is held in escrow. An outbid player is refunded at once and told. A bid in the final 30 seconds extends the auction (anti-snipe). The highest bidder wins when it ends, and the result is settled exactly once, even across servers and restarts. Sellers cannot cancel an auction that has bids.
- **Sales tax and listing fees.** Tax is a percentage taken from the seller's proceeds. Permission tiers can lower it (`donutauction.tax.*`), and the tax can be paid to a server account. The listing fee is a flat amount and/or a percentage, with a cap and an exemption permission. Both are off by default.
- **Price insight.** The sell menu shows recent sales of the item: average, range and last price each, plus the cheapest listing on sale. `/ah price` shows the same for the held item, and the figures are available as PlaceholderAPI placeholders.
- **Sale notifications.** Sellers get a chat message, sound and title the moment something sells, including sales made on another server. If they were offline, they get a "while you were away" summary when they join. Outbid, won, expired and staff-removed notices are included too.
- **A collection that never loses items.** Items are handed over only as whole stacks that fit. A purchase or auction win that does not fit waits in `/ah collect` instead of being dropped on the ground, and won items are delivered on join. Expired and cancelled listings can be reclaimed, relisted with a right-click, or collected all at once.
- **Better browsing.** Search covers names, lore, enchantments, seller names and shulker contents (every word must match). New sorts are Ending Soon and Lowest Price Each. There is a Buy It Now / Auctions filter, `/ah view <player>` (or `/ah <player>`) for one seller's listings, and stacks show their price each.
- **Selling shortcuts.** `/ah sell 2.5k` (also `m`, `b`, `t` and `1,250`). Click the price in the sell menu to type an exact amount. Shift-click the +/- buttons to step ten times faster. The menu also previews your tax and earnings.
- **Rules.** A blacklist by material, name, lore, custom model data and plugin data keys. Disabled worlds and blocked game modes (creative and spectator by default). Per-item minimum and maximum prices, per single item. A listing cooldown. Each rule has a bypass permission.
- **Discord webhooks.** Listings, sales, bids, wins and expiries can be posted as embeds with the item name, amount, enchantments and lore, the price and the traders' heads. Discord's rate limits are respected.
- **PlaceholderAPI.** `%donutauction_active%`, `_limit%`, `_slots_left%`, `_collect%`, `_sold%`, `_earned%`, `_bought%`, `_spent%`, `_total_listings%`, `_total_auctions%`, plus `_average_<MATERIAL>%`, `_sales_<MATERIAL>%`, `_lowest_<MATERIAL>%` and `_listed_<MATERIAL>%`.
- **Developer API and events.** `DonutAuctionApi` (Bukkit services) lists, reads and creates listings and opens menus. Cancellable `AuctionPreListEvent`, `AuctionPrePurchaseEvent` and `AuctionPreBidEvent`. Asynchronous `AuctionListedEvent`, `AuctionPurchasedEvent`, `AuctionBidEvent`, `AuctionWonEvent`, `AuctionEndedEvent` and `AuctionCancelledEvent`.
- **Staff tools.** Shift-right-click any listing to remove it: the item goes to the seller's collection and any held bid is refunded. Also `/ah admin view <player>`, `/ah admin remove <id>`, `/ah admin info`, `/ah admin purge <days>`, and `/ah open <player> [browse|items|history|search <text>]` for NPCs, menus and the console.
- **Transaction log.** One line per trade in `plugins/DonutAuctionHouse/logs/transactions-YYYY-MM-DD.log`.
- **Messages.** MiniMessage, `&` codes and `&#RRGGBB` hex can be mixed. Item names are sent as translatable text, so each player reads them in their own language. Player-made names are always escaped and cannot add colors or click events to broadcasts. Menu text is no longer italic.
- **Sounds** for clicks, purchases, sales, outbids, wins and errors, configurable per event.

## Fixes

- Permission-based listing limits (`donutauction.limit.*`, `donutauction.slots.*`) were never applied: YAML stores dotted permission names as nested sections, so 1.x only read a key named `donutauction`. They now work as documented.
- Fast buy and fast sell preferences are loaded when a player joins. Before, they only worked after toggling them in the same session.
- Returning items and changing inventories now always run on the player's own thread (Folia-safe), never on a database thread.
- Reclaiming expired items is now a conditional row update like buying, so two servers can no longer both return the same item.
- The Redis subscriber reconnects after Redis restarts; MySQL polling covers the gap.
- One unreadable item (for example one written by a newer Minecraft version) no longer stops every other listing from loading. It is skipped, kept in the database and reported in `/ah admin info`.
- Plain Spigot and Bukkit servers now get a clear startup message that DonutAuctionHouse needs Paper or a Paper fork, instead of class-loading errors.

## Upgrade and data

- Replace the jar and restart. `config.yml` and `messages.yml` keep your values; new settings and messages are added automatically. Back up your database first, as always.
- Items are now stored in Paper's binary format, which records the data version and runs Mojang's data fixers on load, so listings survive Minecraft upgrades. Rows written by 1.x stay readable.
- The `auctions` table gains columns for tax, auctions, stored names and the collection, plus a small `auction_meta` table. Sales made before the upgrade appear in history and are not announced again.
- Sold listings are no longer kept in memory or shown in Your Items, because payment always happens at purchase time. They are listed in History > Sold. Your Items now shows active listings and everything waiting to be collected.
- New default: listing in creative or spectator mode is blocked (`restrictions.blocked-gamemodes`, bypass `donutauction.restrictions.bypass`).

## Verification

- 175 automated tests: unit tests, real command and inventory event flows on native SQLite, and the row claims on SQLite and MySQL 8.
- An end-to-end matrix on real servers with network clients: Paper 1.20.1, 1.20.4, 1.20.6, 1.21.1, 1.21.4, 1.21.8, 1.21.11, 26.1.2, 26.2 and 26.3, and Folia 1.20.4, 1.21.4, 1.21.11 and 26.3, with Vault and VaultUnlocked. Each run lists, buys with 10% tax, checks the seller notification, runs an API auction through bid and win, opens history, runs `/ah price` and the console commands, and checks PlaceholderAPI. See `tools/smoke/README.md`.
