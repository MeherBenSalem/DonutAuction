# DonutAuctionHouse

A free, open-source auction house for **Paper**, **Folia** and **Purpur** servers on Minecraft **1.20.1 through 26.3**.

Buy It Now and real auctions with bids, sales tax and listing fees, price insight, sale notifications, a
collection that never loses an item, Discord webhooks, PlaceholderAPI, a developer API, and one shared
market across servers with MySQL (plus optional Redis). Vault or VaultUnlocked economy.

## Features

- **Browse**: a 45-slot market with search by name, lore, enchantment, seller or shulker contents.
  Five sorts (including price each), category and Buy It Now / Auctions filters, `/ah view <player>`,
  and shulker previews.
- **Sell**: `/ah sell 2.5k` (k, m, b and t shorthand) or the sell menu. The menu lets you type an exact
  price, pick a duration and a listing type, and shows recent sale prices and your fees before you list.
- **Auctions with bids**: bids are held in escrow and the outbid player is refunded instantly. Late bids
  extend the auction (anti-snipe). The winner is settled exactly once, even across servers and restarts.
- **Economy**: sales tax with permission tiers and an optional tax account, listing fees, per-item
  minimum and maximum prices, and listing limits by permission.
- **Notifications**: instant sale, outbid, won and expired messages (chat, sound, title), and a
  "while you were away" summary on join.
- **Collection**: items are only handed over as whole stacks that fit. Everything else waits in
  `/ah collect`: purchases with a full inventory, auction wins, expired and removed listings. Relist
  with a right-click.
- **Rules**: a blacklist (material, name, lore, custom model data, plugin data keys), disabled worlds,
  blocked game modes and a listing cooldown, each with a bypass permission.
- **Staff tools**: shift-right-click to remove a listing (the item goes back to the seller, bids are
  refunded), plus `/ah admin view|remove|info|purge` and `/ah open <player>` for NPCs and menus.
- **Integrations**: Discord webhooks, PlaceholderAPI placeholders, `DonutAuctionApi` and Bukkit events,
  bStats, and a daily transaction log.
- **Text**: MiniMessage, `&` codes and hex colors in `messages.yml`. Item names appear in each player's
  own language, and player-made names can never inject formatting.
- **Safe by design**: every move of money or items is a conditional database update that exactly one
  server can win. Items are stored in Paper's versioned format so they survive Minecraft upgrades.
  The plugin is Folia-safe throughout.

## Requirements

- Paper, Folia, Purpur or another Paper fork, **1.20.1–26.3** (plain Spigot/Bukkit lacks Paper's API;
  the plugin says so and disables itself)
- Java **17+** for 1.20.1–1.20.4, **21+** for 1.20.5–1.21.11, **25+** for 26.x (Minecraft's own requirements)
- Vault or VaultUnlocked, plus an economy plugin that registers Vault's Economy service
- Optional: PlaceholderAPI, DonutCore, MySQL and Redis for several servers

## Installation

1. Put `DonutAuctionHouse-<version>.jar` in `plugins/`.
2. Install Vault or VaultUnlocked and your economy plugin.
3. Restart the server.
4. Edit `plugins/DonutAuctionHouse/config.yml` and `messages.yml`, then `/ah reload`.

Upgrading from 1.x: replace the jar and restart; settings, messages and listings are kept (see the
[2.0.0 notes](DonutAuctionHouse-2.0.0-PatchNotes.md)). Back up the database first.

## Commands

| Command | Description |
|---|---|
| `/ah` or `/auction` | Open the auction house |
| `/ah sell <price> [auction]` | List the held item (`1k`, `2.5m`, `1b` work) |
| `/ah auction <starting bid>` | Auction the held item |
| `/ah search <text>` | Search by name, lore, enchantment or seller |
| `/ah view <player>` or `/ah <player>` | One seller's listings |
| `/ah price` | Recent prices of the held item |
| `/ah collect` (`/ah cancel`) | Your listings and items to collect |
| `/ah history [sold]` | Your purchases and sales |
| `/ah limit` | Your listing limit |
| `/ah fastbuy` / `/ah fastsell` | Skip the confirmation menus |
| `/ah help` | Command list |
| `/ah open <player> [browse\|items\|history\|search <text>]` | Open a menu for a player (staff, console, NPCs) |
| `/ah admin view <player>` / `remove <id>` / `info` / `purge <days>` | Staff tools |
| `/ah reload` | Reload config and messages |

## Permissions

| Permission | Default | Description |
|---|---|---|
| `donutauction.use` | everyone | Open the auction house |
| `donutauction.sell` | everyone | List items |
| `donutauction.sell.auction` | everyone | Start auctions with bids (`bidding.permission`) |
| `donutauction.fastbuy` / `.fastsell` | everyone | Toggle fast buy / fast sell |
| `donutauction.limit.<n>` / `.unlimited` | — | Listing limits (`auction-limits`) |
| `donutauction.slots.<n>` | — | Listing slot expansions (`auction-slots`) |
| `donutauction.tax.<tier>` | — | Sales-tax tiers (`economy.sales-tax.permissions`) |
| `donutauction.fee.exempt` | — | No listing fee |
| `donutauction.broadcast.receive` | everyone | Receive new-listing broadcasts |
| `donutauction.admin` | op | Staff tools, `/ah reload`, removing listings |
| `donutauction.admin.open` | op | `/ah open <player>` |
| `donutauction.blacklist.bypass` / `.restrictions.bypass` / `.cooldown.bypass` | op | Skip the listing rules |
| `donutauction.update.notify` | op | Update notices |

The 1.x `donutcore.auction.*` nodes still work.

## PlaceholderAPI

`%donutauction_active%`, `%donutauction_limit%`, `%donutauction_slots_left%`, `%donutauction_collect%`,
`%donutauction_sold%`, `%donutauction_earned%`, `%donutauction_bought%`, `%donutauction_spent%`,
`%donutauction_total_listings%`, `%donutauction_total_auctions%`, and per material
`%donutauction_average_DIAMOND%`, `%donutauction_sales_DIAMOND%`, `%donutauction_lowest_DIAMOND%`,
`%donutauction_listed_DIAMOND%`. Add `_raw` to money placeholders for a plain number.

## Developer API

```java
DonutAuctionApi.get().ifPresent(api -> {
    api.activeListings();                                   // what is for sale
    api.marketStats(Material.DIAMOND).thenAccept(stats -> { /* average price each, sales */ });
    api.createListing(sellerId, "Crates", item, 5000, Duration.ofHours(24), ListingType.BIN);
});
```

Events in `io.nightbeam.donutauction.api.event`: `AuctionPreListEvent`, `AuctionPrePurchaseEvent` and
`AuctionPreBidEvent` (cancellable), and `AuctionListedEvent`, `AuctionPurchasedEvent`, `AuctionBidEvent`,
`AuctionWonEvent`, `AuctionEndedEvent` and `AuctionCancelledEvent` (asynchronous). Add
`softdepend: [DonutAuctionHouse]` to your plugin.yml.

## Price display and transaction history

Economy-provider price formatting remains the default. Set `price-display.compact.enabled: true`
to abbreviate browse and Your Items prices. `precision` (0-6), `thousand-suffix`,
`million-suffix`, `locale` (BCP 47, default `en-US`), and `template` are configurable.
The template supports `%amount%`, `%suffix%`, and `%currency%` (the provider's plural currency name).
Amounts below 1000 keep provider formatting. Rounding can promote `999.95k` to `1M`;
amounts beyond a million continue using M. This is presentation only: listing, charge,
payment and stored prices retain their original double values. Confirmations show the
provider's formatted price plus the full decimal amount in parentheses, even if the provider rounds.

Click the book at the bottom of the auction browser (or `/ah history`) to open transaction history.
Bought/Sold tabs, previous/next arrows, Refresh and Back are read-only. Every page contains only the
viewer's completed purchases or sales, with item, exact price, tax, date and buyer/seller. Dates default
to UTC; configure `history.timezone` and `history.date-format` as needed. `history.retention-days`
deletes old settled history (off by default).

## Several servers

Set `storage.type: MYSQL` on every server and point them at the same database. Each purchase, bid,
auction end and reclaim is a conditional row update, so two servers can never sell, settle or return the
same item twice. Changes reach other servers through MySQL polling (`sync.poll-interval-seconds`) or,
immediately, through Redis (`sync.redis`). Sellers are told about a sale on whichever server they are on.

## Building and testing

```bash
mvn package            # target/DonutAuctionHouse-<version>.jar, runs the unit and flow tests
```

`mvn test` runs the unit tests, the command and inventory flows on native SQLite, and the database
claims (add `-Dauction.test.mysql.url=jdbc:mysql://localhost:3306/<db>` for MySQL). The cross-version
matrix on real Paper and Folia servers with network clients is in [`tools/smoke`](tools/smoke/README.md).

Release tagging (Modrinth / CurseForge, and Hangar if you upload there) must use every loader and
Minecraft version in [`release/supported-minecraft.json`](release/supported-minecraft.json). Listing copy
and artwork are in [`marketing/`](marketing/README.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

See [SECURITY.md](.github/SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
