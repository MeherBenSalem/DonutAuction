# Graph Report - DonutAuction  (2026-07-29)

## Corpus Check
- 58 files · ~15,140 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 604 nodes · 1545 edges · 20 communities (18 shown, 2 thin omitted)
- Extraction: 83% EXTRACTED · 17% INFERRED · 0% AMBIGUOUS · INFERRED: 266 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `1ff2792e`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- GuiManager
- AuctionHousePlugin
- PlayerPreferenceManager
- AuctionListing
- PlayerAuctionGui.java
- PendingSaleTransaction
- AuctionFilterCategory
- AuctionService
- ItemBuilder
- BaseGui
- SellGui
- EconomyBridgeDetector
- 1.2.0 — Quality of Life & Marketplace Expansion Update
- ReflectiveDonutCoreHook
- DonutAuctionHouse v1.2.2
- DonutAuctionHouse v1.3.0
- MessageUtil
- Test
- ListingPriceValidationResult
- io.nightbeam:donutauction

## God Nodes (most connected - your core abstractions)
1. `AuctionService` - 68 edges
2. `GuiManager` - 56 edges
3. `AuctionListing` - 52 edges
4. `AuctionHousePlugin` - 34 edges
5. `PlayerPreferenceManager` - 28 edges
6. `AuctionFilterCategory` - 25 edges
7. `AuctionManager` - 25 edges
8. `PlayerPreference` - 22 edges
9. `SellGui` - 21 edges
10. `PendingSaleTransaction` - 20 edges

## Surprising Connections (you probably didn't know these)
- `comparator()` --references--> `AuctionListing`  [EXTRACTED]
  src/main/java/io/nightbeam/donutauction/model/AuctionSortMode.java → src/main/java/io/nightbeam/donutauction/model/AuctionListing.java
- `AuctionHousePlugin` --references--> `VaultEconomyProvider`  [EXTRACTED]
  src/main/java/io/nightbeam/donutauction/AuctionHousePlugin.java → src/main/java/io/nightbeam/donutauction/economy/VaultEconomyProvider.java
- `AuctionHousePlugin` --references--> `GuiManager`  [EXTRACTED]
  src/main/java/io/nightbeam/donutauction/AuctionHousePlugin.java → src/main/java/io/nightbeam/donutauction/gui/GuiManager.java
- `AuctionHousePlugin` --references--> `AuctionLimitService`  [EXTRACTED]
  src/main/java/io/nightbeam/donutauction/AuctionHousePlugin.java → src/main/java/io/nightbeam/donutauction/service/AuctionLimitService.java
- `AuctionHousePlugin` --references--> `AuctionManager`  [EXTRACTED]
  src/main/java/io/nightbeam/donutauction/AuctionHousePlugin.java → src/main/java/io/nightbeam/donutauction/service/AuctionManager.java

## Import Cycles
- None detected.

## Communities (20 total, 2 thin omitted)

### Community 0 - "GuiManager"
Cohesion: 0.07
Nodes (29): AsyncChatEvent, InventoryCloseEvent, InventoryDragEvent, Listener, PlayerQuitEvent, AuctionGui, Component, Inventory (+21 more)

### Community 1 - "AuctionHousePlugin"
Cohesion: 0.05
Nodes (22): Entity, HttpClient, JavaPlugin, PlayerJoinEvent, AuctionHousePlugin, Override, DonutCoreHook, Component (+14 more)

### Community 2 - "PlayerPreferenceManager"
Cohesion: 0.08
Nodes (12): Command, CommandExecutor, AuctionCommand, CommandSender, Override, Player, PlayerPreference, AuctionLimitService (+4 more)

### Community 3 - "AuctionListing"
Cohesion: 0.07
Nodes (18): DataSource, HikariDataSource, PreparedStatement, ResultSet, AuctionListing, ItemStack, AuctionRepository, DatabaseManager (+10 more)

### Community 4 - "PlayerAuctionGui.java"
Cohesion: 0.06
Nodes (25): NamedTextColor, ItemStack, Component, Inventory, InventoryClickEvent, ItemStack, Override, Player (+17 more)

### Community 5 - "PendingSaleTransaction"
Cohesion: 0.13
Nodes (16): BeforeEach, Logger, ItemStack, PendingSaleTransaction, BeginResult, ClaimResult, ClaimResultType, NOT_FOUND (+8 more)

### Community 6 - "AuctionFilterCategory"
Cohesion: 0.06
Nodes (26): PlainTextComponentSerializer, AuctionBrowseRequest, AuctionFilterCategory, ALL, BLOCKS, BOOKS, COMBAT, FOOD (+18 more)

### Community 7 - "AuctionService"
Cohesion: 0.11
Nodes (11): Economy, EconomyResponse, JavaPlugin, OfflinePlayer, VaultEconomyProvider, ActionResult, AuctionService, ItemStack (+3 more)

### Community 8 - "ItemBuilder"
Cohesion: 0.15
Nodes (11): ItemMeta, ConfirmPurchaseGui, Component, Inventory, InventoryClickEvent, Override, Player, ItemBuilder (+3 more)

### Community 9 - "BaseGui"
Cohesion: 0.16
Nodes (13): InventoryHolder, BaseGui, Inventory, InventoryClickEvent, Override, Player, Component, Inventory (+5 more)

### Community 10 - "SellGui"
Cohesion: 0.14
Nodes (9): Component, Inventory, InventoryClickEvent, ItemStack, Override, Player, SellGui, Test (+1 more)

### Community 11 - "EconomyBridgeDetector"
Cohesion: 0.24
Nodes (5): PluginManager, EconomyBridgeDetector, Plugin, EconomyBridgeDetectorTest, Test

### Community 12 - "1.2.0 — Quality of Life & Marketplace Expansion Update"
Cohesion: 0.12
Nodes (15): 1.2.0 — Quality of Life & Marketplace Expansion Update, 1.3.0 — Sell GUI fixes & marketplace QoL, DonutAuctionHouse Patch Notes, Feature 1 — Permission-Based Auction Limits, Feature 2 — Preserve Original Item Lore, Feature 3 — Fast Buy System, Feature 4 — Fast Sell System, Feature 5 — Shulker Box Support (+7 more)

### Community 13 - "ReflectiveDonutCoreHook"
Cohesion: 0.33
Nodes (5): Method, Override, Player, Plugin, ReflectiveDonutCoreHook

### Community 14 - "DonutAuctionHouse v1.2.2"
Cohesion: 0.25
Nodes (7): Bug Fixes, Compatibility, Configuration, DonutAuctionHouse v1.2.2, Improvements, New Features, Upgrade Notes

### Community 15 - "DonutAuctionHouse v1.3.0"
Cohesion: 0.25
Nodes (7): Bug Fixes, Compatibility, Configuration, DonutAuctionHouse v1.3.0, Improvements, New Features, Upgrade Notes

### Community 16 - "MessageUtil"
Cohesion: 0.39
Nodes (4): LegacyComponentSerializer, CommandSender, Component, MessageUtil

### Community 18 - "ListingPriceValidationResult"
Cohesion: 0.40
Nodes (5): ListingPriceValidationResult, BELOW_MINIMUM, INVALID_OR_ABOVE_MAX, VALID, validate()

## Knowledge Gaps
- **54 isolated node(s):** `io.nightbeam:donutauction`, `ALL`, `BLOCKS`, `TOOLS`, `FOOD` (+49 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AuctionService` connect `AuctionService` to `GuiManager`, `AuctionHousePlugin`, `PlayerPreferenceManager`, `AuctionListing`, `PlayerAuctionGui.java`, `PendingSaleTransaction`, `AuctionFilterCategory`, `ItemBuilder`, `SellGui`?**
  _High betweenness centrality (0.229) - this node is a cross-community bridge._
- **Why does `GuiManager` connect `GuiManager` to `AuctionHousePlugin`, `PlayerPreferenceManager`, `PlayerAuctionGui.java`, `AuctionFilterCategory`, `AuctionService`, `ItemBuilder`, `BaseGui`, `SellGui`?**
  _High betweenness centrality (0.126) - this node is a cross-community bridge._
- **Why does `AuctionListing` connect `AuctionListing` to `GuiManager`, `PlayerAuctionGui.java`, `AuctionFilterCategory`, `AuctionService`, `ItemBuilder`?**
  _High betweenness centrality (0.119) - this node is a cross-community bridge._
- **What connects `io.nightbeam:donutauction`, `ALL`, `BLOCKS` to the rest of the system?**
  _54 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `GuiManager` be split into smaller, more focused modules?**
  _Cohesion score 0.0650103519668737 - nodes in this community are weakly interconnected._
- **Should `AuctionHousePlugin` be split into smaller, more focused modules?**
  _Cohesion score 0.053410893707033315 - nodes in this community are weakly interconnected._
- **Should `PlayerPreferenceManager` be split into smaller, more focused modules?**
  _Cohesion score 0.08361581920903954 - nodes in this community are weakly interconnected._