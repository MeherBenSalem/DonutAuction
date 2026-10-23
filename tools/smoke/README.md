# Cross-version smoke matrix

Boots real servers across the supported range and plays the auction house with
[mineflayer](https://github.com/PrismarineJS/mineflayer) bots over the network protocol, so every check
goes through the real plugin, the real economy bridge and real inventory packets.

| Target | Java | Bot protocol | Economy bridge |
| --- | --- | --- | --- |
| Paper 1.20.1, 1.20.4 | 17 | same version | Vault / VaultUnlocked |
| Paper 1.20.6, 1.21.1, 1.21.4, 1.21.8, 1.21.11 | 21 | same version | alternating |
| Paper 26.1.2, 26.2, 26.3 | 25 | 26.1 through ViaVersion + ViaBackwards | alternating |
| Folia 1.20.4, 1.21.4, 1.21.11, 26.3 | 17 / 21 / 25 | as above | VaultUnlocked |

Each target runs one scenario (`scenario.mjs`) with PlaceholderAPI installed and a smoke `config.yml`
(10% sales tax paid to a `Bank` account, a 5 second expiry scan, broadcasts on):

1. The plugin enables, registers its public API and its PlaceholderAPI expansion.
2. `Seller` runs `/ah sell 250` with three diamonds and confirms in the sell menu.
3. `Buyer` finds them in `/ah`, confirms and buys. Checked: items delivered, buyer charged 250,
   seller paid 225, the tax account paid 25, and the seller's "sold" message.
4. The fixture creates a 65 second auction through `DonutAuctionApi`. `Buyer` bids through the bid menu
   (money held), wins when it ends, gets the item automatically and the seller is paid after tax.
5. `/ah history`, `/ah price`, console `/ah admin info` and `papi parse` answer.
6. The console is scanned for plugin errors (severe lines, stack frames in `io.nightbeam.donutauction`,
   linkage errors).

## Running it

```bash
mvn -B -DskipTests package
mvn -B -f tools/smoke/fixture/pom.xml package -Ddonutauction.version=2.0.0 \
    -Ddonutauction.jar="$PWD/target/DonutAuctionHouse-2.0.0.jar"
npm --prefix tools/smoke install
node tools/smoke/run.mjs                       # all targets (about 45 minutes)
node tools/smoke/run.mjs paper-1.20.1 folia-26.3  # selected targets
```

JDKs (Temurin 17/21/25), server jars (PaperMC Fill API), Vault, VaultUnlocked, PlaceholderAPI and
ViaVersion/ViaBackwards are downloaded on first use and cached in `$AUCTIONLAB_HOME`
(default `~/.cache/donutauction-smoke`). Servers listen on `$AUCTIONLAB_PORT` (default 25599). A JSON
report is written to `$AUCTIONLAB_HOME/reports/`. `node tools/smoke/compat.mjs` turns the newest report
into `marketing/source/compat.json` for the compatibility card.

`fixture/` is a test-only plugin: an in-memory Vault economy and console helpers (`lab money`, `lab give`,
`lab count`, `lab balance`, `lab sudo`, `lab auction`, `lab seed`, `lab order`). Never install it on a
real server.
