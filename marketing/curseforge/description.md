![DonutAuctionHouse 2.0: The Market Update](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/banner.png)

## The auction house your players will actually use

**DonutAuctionHouse** is a free, open-source auction house for Paper, Folia and Purpur, from Minecraft **1.20.1 to 26.3**. Players open `/ah` and get a clean 45-slot market. They can search it by name, enchantment or seller, sell anything in a few clicks with `/ah sell 2.5k`, or put rare items up for a real auction with bids. Sellers are told the moment something sells, even while they are on another server or offline. Nothing is ever dropped on the ground. Every purchase, bid and reclaim is a database claim that only one player, on one server, can win.

**2.0 "The Market Update"** adds auctions with bids, sales tax and listing fees, price insight, sale notifications, the collection, Discord webhooks, PlaceholderAPI, a developer API and staff tools. Each release is tested on every supported Paper and Folia version with real clients.

![Browsing and buying on the market](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/gifs/buy.gif)

![divider](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/divider.png)

![The Market](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-market.png)

![The whole market in one menu](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-market.png)

- **Search everything**: item names, lore, enchantments (`sharpness`), seller names and the contents of shulker boxes. Every word must match.
- **Sort** by Recently Listed, Ending Soon, Lowest Price, Highest Price or **Lowest Price Each**. Stacks show their price per item, so a stack of 64 can be compared with a single.
- **Filter** by category (blocks, tools, food, combat, potions, books, ingredients, utilities) and by type: Buy It Now or auctions.
- **One seller's shop** with `/ah view <player>` (or just `/ah <player>`).
- **Shulker preview**: right-click a shulker box to see what is inside before you buy.
- Item names are shown in each player's own language.

![Selling](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-selling.png)

![Sell in seconds](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-selling.png)

1. Hold the item and run `/ah sell 2500`. `2.5k`, `1.2m`, `3b` and `1,250` work too.
2. The sell menu opens with your price. Click it to type an exact amount, or use +/- (shift-click for bigger steps).
3. Pick a duration (6 hours to a week, configurable) and **Buy It Now** or **Auction**.
4. Check **Price Insight** (what it sold for lately) and **Fees & Earnings** (tax, fee, what you receive), then confirm.

Fast sell (`/ah fastsell`) skips the menu and lists at once with your last duration.

![Auctions](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-auctions.png)

![Real auctions, done right](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-auctions.png)

- Start one with `/ah auction <starting bid>`, or switch the sell menu to **Auction**.
- The bid menu offers the minimum next bid, +10%, +25% or a typed amount. A new bid must beat the current one by 5% or 1, whichever is more (configurable).
- **Escrow**: a bid is taken from the bidder when they place it. If someone outbids them, it is refunded instantly and they are told.
- **Anti-snipe**: a bid in the final 30 seconds extends the auction, so last-second sniping does not work.
- **Settled once**: when the auction ends, the highest bidder wins and the seller is paid (minus tax). This happens exactly once, even with several servers sharing MySQL, and an interrupted payout is completed on the next start.
- The winner gets the item straight away if they are online and it fits; otherwise it waits in `/ah collect`.

![Price Insight](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-insight.png)

![Know what it is worth](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-insight.png)

- The sell menu shows the **average**, **range** and **last** sale price per item for the last 7 days, and the **cheapest** listing on sale right now.
- `/ah price` shows the same for whatever you hold, including what your whole stack is worth.
- The same figures are available as PlaceholderAPI placeholders for scoreboards and holograms: `%donutauction_average_DIAMOND%`, `%donutauction_lowest_DIAMOND%` and more.
- Built in and free. Other auction plugins sell price history as a paid add-on.

![Notifications](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-notifications.png)

![Never miss a sale](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-notifications.png)

- **Sold**: a chat message, a sound and an *ITEM SOLD* title the moment someone buys your item, with the buyer, the price and the tax.
- **While you were away**: sales made while you were offline are summarised when you join, with the total earned.
- **Outbid**, **won**, **expired** and **removed by staff** notices.
- Works across servers: you are told on whichever server you are on.
- Optional chat broadcasts for new listings (minimum price and cooldown configurable).

![Collection](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-collection.png)

![Nothing is ever lost](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-collection.png)

- Items are handed over **only as whole stacks that fit**. A purchase made with a full inventory, an auction won while offline, an expired listing or one removed by staff waits in `/ah collect`. Nothing is dropped on the ground.
- **Collect All** takes everything that fits in one click.
- **Right-click** an expired listing to relist it at the same price, or left-click to take it back.
- Your Items shows your active listings, your limit, and how much you have sold and bought.

![Economy & Rules](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-economy.png)

![Your economy, your rules](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-economy.png)

| Setting | What it does |
| --- | --- |
| `economy.sales-tax` | A percentage taken from the seller's proceeds. Permission tiers lower it (`donutauction.tax.vip: 2.5`), and `deposit-to` pays it to a server account. |
| `economy.listing-fee` | A flat fee and/or a percentage when listing, with a cap and `donutauction.fee.exempt`. |
| `auction.price-limits` | Minimum and maximum price per single item, for example `DIAMOND: {min: 50}`. |
| `auction-limits` | Listings per player by permission (`donutauction.limit.25`, `.unlimited`). |
| `blacklist` | Materials, text in names or lore, custom model data and plugin data keys that can never be sold. |
| `restrictions` | Disabled worlds and blocked game modes (creative and spectator by default), plus a listing cooldown. |

Tax and fees are off until you enable them. Every rule has a bypass permission for staff.

![Built to Be Safe](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-safety.png)

![Dupe-safe by design](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-safety.png)

- **One winner per action.** Buying, bidding, ending an auction, cancelling, reclaiming and collecting each start with a conditional database update that only one player on one server can win. Double clicks, lag and two servers at once cannot sell or return an item twice.
- **The item is always somewhere**: in a hand, in a pending sale, on the market or in a collection. It is never in two places, and never on the ground.
- **Folia-safe**: inventories only change on the player's own thread.
- **Upgrade-proof**: items are stored in Paper's versioned format and run through Mojang's data fixers, so listings survive Minecraft updates. An item that cannot be read is set aside and reported; it never blocks the rest.
- **One market, many servers**: MySQL shares listings and Redis (optional) makes updates instant.

![Integrations](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-integrations.png)

![Plays well with others](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-integrations.png)

- **Discord webhooks**: new listings, sales, bids, wins and expiries as embeds with the item, amount, enchantments, price and the seller's head. Pick the events and a minimum price.
- **PlaceholderAPI**:

| Placeholder | Value |
| --- | --- |
| `%donutauction_active%`, `%donutauction_limit%`, `%donutauction_slots_left%` | Your listings and limit |
| `%donutauction_collect%` | Items waiting in `/ah collect` |
| `%donutauction_sold%`, `%donutauction_earned%` | Items sold and money earned (after tax) |
| `%donutauction_bought%`, `%donutauction_spent%` | Items bought and money spent |
| `%donutauction_total_listings%`, `%donutauction_total_auctions%` | The whole market |
| `%donutauction_average_<ITEM>%`, `_lowest_<ITEM>%`, `_sales_<ITEM>%`, `_listed_<ITEM>%` | Price insight per item |

- **Developer API**: `DonutAuctionApi` lists, reads and creates listings (crates, NPC shops, rewards). It comes with cancellable pre-list, pre-purchase and pre-bid events, plus listed, purchased, bid, won, ended and cancelled events.
- Vault and VaultUnlocked economies, DonutCore display names, and bStats.

![Staff Tools](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-staff.png)

![Moderate the market](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-staff.png)

- **Shift-right-click** any listing to remove it. The item goes back to the seller's collection, any held bid is refunded, and the seller is told.
- `/ah admin view <player>`: a player's listings, click to remove.
- `/ah admin remove <id>`, `/ah admin info` (storage, active listings, unreadable rows) and `/ah admin purge <days>` (old history).
- `/ah open <player> [browse|items|history|search <text>]` opens a menu for a player, from the console, an NPC or a menu plugin.
- A daily **transaction log** in `plugins/DonutAuctionHouse/logs/` records every listing, sale, bid, win, cancel and reclaim.

![Configuration](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-config.png)

![Make it yours](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-config.png)

- `config.yml`: durations, prices, bidding, tax and fees, notifications, broadcasts, rules, sounds, Discord, storage and sync. New settings are added automatically when you update; your values are kept.
- `messages.yml`: every word players see, including menu titles and buttons. Use **MiniMessage** (`<gradient:#ff6fae:#ffc24b>`), `&` codes or `&#RRGGBB` hex, mixed freely. Player-made names are always inserted as plain text, so they can never add colors or click events.
- `/ah reload` applies both without a restart.

![Compatibility](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-compatibility.png)

![Tested on every version](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/card-compatibility.png)

| Server | Minecraft | Java | Notes |
| --- | --- | --- | --- |
| Paper, Purpur, other Paper forks | 1.20.1 – 1.20.4 | 17+ | |
| Paper, Purpur, other Paper forks | 1.20.5 – 1.21.11 | 21+ | |
| Paper, Purpur, other Paper forks | 26.1 – 26.3 | 25+ | |
| Folia | 1.20.4 – 26.3 | as above | Region-thread safe |
| Spigot, Bukkit | — | — | Not supported: they lack Paper's API. The plugin says so and disables itself. |

- Needs **Vault** or **VaultUnlocked** and an economy plugin (EssentialsX, CMI, and others).
- Optional: PlaceholderAPI, DonutCore, MySQL and Redis.
- One jar for every version. Install it on the server only; players need no mods.
- Each release runs an automated matrix on real Paper 1.20.1, 1.20.4, 1.20.6, 1.21.1, 1.21.4, 1.21.8, 1.21.11, 26.1.2, 26.2 and 26.3 servers and Folia 1.20.4, 1.21.4, 1.21.11 and 26.3, with real clients listing, buying, bidding and winning.

![Commands & Permissions](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-commands.png)

| Command | What it does | Permission |
| --- | --- | --- |
| `/ah` | Open the auction house | `donutauction.use` |
| `/ah sell <price> [auction]` | Sell the held item | `donutauction.sell` |
| `/ah auction <starting bid>` | Auction the held item | `donutauction.sell.auction` |
| `/ah search <text>` | Search by name, enchantment or seller | `donutauction.use` |
| `/ah view <player>` | One seller's listings | `donutauction.use` |
| `/ah price` | Recent prices of the held item | `donutauction.use` |
| `/ah collect` | Your listings and items to collect | `donutauction.use` |
| `/ah history [sold]` | Your purchases and sales | `donutauction.use` |
| `/ah limit` | Your listing limit | `donutauction.use` |
| `/ah fastbuy`, `/ah fastsell` | Skip the confirmation menus | `donutauction.fastbuy`, `.fastsell` |
| `/ah open <player> [menu]` | Open a menu for a player | `donutauction.admin.open` |
| `/ah admin view\|remove\|info\|purge` | Staff tools | `donutauction.admin` |
| `/ah reload` | Reload config and messages | `donutauction.admin` |

The 1.x `donutcore.auction.*` permissions keep working.

![Questions](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-faq.png)

**Is it really free?**
Yes, all of it: auctions, tax, price insight, webhooks, PlaceholderAPI, cross-server and the API. The source is on GitHub under Apache-2.0.

**I'm updating from 1.x. Will I lose anything?**
No. Replace the jar and restart. Your listings, history, config values and messages are kept, and new settings are added. Back up your database first, as with any update.

**Does it work on Spigot?**
No. DonutAuctionHouse uses Paper's API (Adventure text, region schedulers). Paper is a drop-in replacement for Spigot, and Purpur and other Paper forks work too.

**Can players dupe items with it?**
Every purchase, bid, auction end, cancel and reclaim is a conditional database update that only one action can win. Items only move as whole stacks that fit, so nothing is dropped where it could be picked up twice. If you ever find a way, please report it privately (see SECURITY.md on GitHub).

**How do I share one market between servers?**
Set `storage.type: MYSQL` on every server with the same database. Optionally enable `sync.redis` for instant updates. Sales notifications follow the player to whichever server they are on.

**Can I charge tax only to some players?**
Yes. Set `economy.sales-tax.percent` for everyone and add tiers under `economy.sales-tax.permissions`, for example `donutauction.tax.vip: 2.5`. The lowest matching tier applies.

**Can I stop people selling certain items?**
Use `blacklist` (materials, names, lore, custom model data, plugin data keys) and `auction.price-limits` for price floors and ceilings per item.

**Where are the docs?**
[README](https://github.com/MeherBenSalem/DonutAuction#readme) · [Release notes](https://github.com/MeherBenSalem/DonutAuction/blob/main/DonutAuctionHouse-2.0.0-PatchNotes.md) · [Issues](https://github.com/MeherBenSalem/DonutAuction/issues)

![Gallery](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/header-gallery.png)

![The market, with a listing's enchantments, price, seller and time left](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/browse-hover.jpg)

![An auction with a live bid](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/browse-auction-hover.jpg)

![Shulker preview before buying](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/shulker-preview.jpg)

![Five sort orders](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/browse-sort.jpg)

![Category and listing-type filters](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/filter-menu.jpg)

![Confirm a purchase, with your balance afterwards](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/confirm-purchase.jpg)

![The bid menu](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/bid-menu.jpg)

![A bid placed: held in escrow, refunded if someone outbids you](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/bid-placed.jpg)

![Price insight in the sell menu](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/sell-insight.jpg)

![Fees and earnings before you list](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/sell-fees.jpg)

![Your Items: expired listings to reclaim or relist](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/your-items.jpg)

![Your trading summary](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/your-items-summary.jpg)

![The moment your item sells](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/sold-title.jpg)

![/ah price for the held item](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/price-check.jpg)

![Transaction history](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/history.jpg)

![Staff view: shift-right-click to remove a listing](https://raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/screenshots/admin-browse.jpg)

---

DonutAuctionHouse is licensed under Apache-2.0. The Outfit and Inter fonts used in this artwork are licensed under the SIL Open Font License 1.1. Screenshots are unedited captures of the vanilla Minecraft client on a Paper server running the plugin. DonutAuctionHouse is an independent project and is not affiliated with Mojang, Microsoft or any server it takes inspiration from.
