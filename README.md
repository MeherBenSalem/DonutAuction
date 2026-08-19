# DonutAuctionHouse

Standalone auction house plugin for **Paper**, **Folia**, **Purpur**, **Spigot**, and **Bukkit**, Minecraft **1.20.1 through 26.2**.

List items, browse the market, buy and sell with a Vault-compatible economy. Optional DonutCore integration.

## Features

- Auction GUI with filters, sorting, and player listings
- Sell GUI with price steps and shulker-box preview
- Fast buy / fast sell preferences
- Permission-based listing limits and slot expansions
- SQLite or MySQL storage
- Folia-safe region scheduling (Bukkit scheduler fallback when needed)
- Vault and VaultUnlocked economy detection
- bStats (plugin ID 33523)
- Modrinth update checker for operators on load and join

## Requirements

- Paper, Folia, or Purpur **1.20.1–26.2** (Spigot/Bukkit tagged on stores; Paper-family servers are first-class)
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
