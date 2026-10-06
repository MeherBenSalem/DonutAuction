# DonutAuctionHouse

Standalone auction house plugin for **Paper**, **Folia**, **Purpur**, **Spigot**, and **Bukkit**, Minecraft **1.20.1 through 26.3**.

List items, browse the market, buy and sell with a Vault-compatible economy. Optional DonutCore integration.

## Features

- Auction GUI with filters, sorting, and player listings
- Optional compact k/M prices, with precise amounts in buy/sell confirmation
- Private, read-only Bought/Sold transaction history from the browser's book button
- Sell GUI with price steps and shulker-box preview
- Fast buy / fast sell preferences
- Permission-based listing limits and slot expansions
- SQLite or MySQL storage (MySQL is safe for multi-server; optional Redis to refresh other nodes immediately)
- Folia-safe region scheduling (Bukkit scheduler fallback when needed)
- Vault and VaultUnlocked economy detection
- bStats (plugin ID 33523)
- Modrinth update checker for operators on load and join

## Requirements

- Paper, Folia, or Purpur **1.20.1–26.3** (Spigot/Bukkit tagged on stores; Paper-family servers are first-class)
- Java **17+** (Java 21 is typical on 1.20.5+)
- Vault or VaultUnlocked plus an economy plugin that registers Vault's Economy service
- Optional: DonutCore

## Installation

1. Put `DonutAuctionHouse-<version>.jar` in `plugins/`.
2. Install Vault or VaultUnlocked and your economy plugin.
3. Restart the server.
4. Edit `plugins/DonutAuctionHouse/config.yml` and `messages.yml`, then `/ah reload`.

## Usage

| Command | Description |
|---|---|
| `/ah` or `/auction` | Open the auction house |
| `/ah sell <price>` | List the held item |
| `/ah cancel` | Cancel your listings from the GUI flow |
| `/ah reload` | Reload config and messages (admin) |
| `/ah limit` | Show your listing limit |
| `/ah fastbuy` / `/ah fastsell` | Toggle quick buy/sell |

Permission nodes are listed in `plugin.yml` (`donutauction.*` and legacy `donutcore.auction.*`).

## Price display and transaction history

Economy-provider price formatting remains the default. Set `price-display.compact.enabled: true`
to abbreviate browse and Your Items prices. `precision` (0-6), `thousand-suffix`,
`million-suffix`, `locale` (BCP 47, default `en-US`), and `template` are configurable.
The template supports `%amount%`, `%suffix%`, and `%currency%` (the provider's plural currency name).
Amounts below 1000 keep provider formatting. Rounding can promote `999.95k` to `1M`;
amounts beyond a million continue using M. This is presentation only: listing, charge,
payment and stored prices retain their original double values. Confirmations show the
provider's formatted price plus the full decimal amount in parentheses, even if the provider rounds.

Click the book at the bottom of the auction browser to open transaction history.
Bought/Sold tabs, previous/next arrows, Refresh and Back are read-only. Every page contains
only the viewer's completed purchases or sales, with item, exact price, date and buyer/seller.
Dates default to UTC; configure `history.timezone` and `history.date-format` as needed.
Unknown names use UUIDs, and unavailable item metadata uses a placeholder. Refresh includes
new purchases; an old asynchronous response cannot reopen a menu you have left.

History reuses the `auctions` table. An additive `purchase_completed` flag distinguishes
new successful purchases from pre-payment SOLD claims; no parallel ledger or payment system
is created. Existing SOLD records are retained as legacy completed records (old releases did
not persist a separate settlement flag). Cancelled, active, expired and failed new purchases
are excluded. If completion bookkeeping fails after payment/delivery, the purchase stays
successful and the affected record may be absent; the server logs its auction ID for diagnosis.
Keep existing config and database files when upgrading. Back up the database before upgrades.

If `/ah sell <price>` opens another plugin's browser, compare
`/donutauctionhouse:ah sell <price>`. Include the installed DonutAuctionHouse version, server
software/version, exact command, main-hand item, both outcomes and logs in the report.
An alias collision can reproduce that symptom; the customer-specific cause has not been established.

## Building

```bash
mvn package
```

Output: `target/DonutAuctionHouse-<version>.jar`

Release tagging (Modrinth / CurseForge, and Hangar if you upload there) must use every loader and Minecraft version in [`release/supported-minecraft.json`](release/supported-minecraft.json).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

See [SECURITY.md](.github/SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
