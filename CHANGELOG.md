# Changelog

## 1.6.0

- Optional locale-aware k/M price display; existing provider formatting remains the default and exact amounts remain visible in confirmations.
- Private, paginated Bought/Sold history via the auction browser book; completed transactions only, with service/query ownership checks and asynchronous database reads.
- Additive completion flag in existing auction storage preserves legacy SOLD rows and excludes new unsettled claims; history writes do not alter settlement.
- Reject non-finite/out-of-range sell prices before taking the held item; retain alias/confirmation/fast-sell regression coverage. The customer-specific browser-opening report remains unresolved.
- Portable database index initialization and visible migration failures.
- A confirmation-formatting failure after payment and item delivery cannot reopen a completed sale; completed purchase claims reject late release attempts.
- Java 17/21, native SQLite/MySQL, GUI events, monetary precision and release receipt checks; immutable artifacts and guarded partial-upload recovery.

## 1.5.2

- Browse GUI previous-page arrow restored at slot 46 (sell held item stays at 45; next page stays at 53)
- Page arrows show `page/totalPages` and total listing count
- Sort, filter, and search reset the browse session to page 1
- `AuctionManager.browse()` clamps page to at least 1 so a non-positive page cannot throw

## 1.5.1

- Store tags extended to Minecraft **26.3** (legacy 1.20.1–26.2 unchanged)

## 1.5.0

- Shared MySQL listings are claimed with `UPDATE ... WHERE status = ACTIVE` before Vault is charged, so two Folia nodes cannot sell the same auction twice
- Optional Redis pub/sub (`sync.redis`) notifies other nodes immediately; MySQL poll (`sync.poll-interval-seconds`) is the fallback
- Cancel and expire also use conditional row claims
- Purchase settlement runs on the buyer entity thread (Folia-safe)

## 1.4.0

- bStats metrics (plugin ID 33523), disable with `metrics.enabled: false`
- Store tags: Paper, Folia, Purpur, Spigot, and Bukkit for Minecraft 1.20.1–26.2
- Modrinth update check always reports on load (console + online admins): up to date or update available
- Admins with `donutauction.update.notify` still see update notices on join when a newer release exists
- Chat search uses Bukkit `AsyncPlayerChatEvent`; scheduler falls back when region schedulers are absent

## 1.3.1

- Full `messages.yml` translation support for GUI titles, button labels, lore, chat, commands, and status labels
- `/ah reload` reloads `messages.yml` with `config.yml`
- Prefix and minimum-price messages can live in `messages.yml` (legacy `config.yml` keys still work)

## 1.3.0

- Sell GUI expanded to 45 slots; confirm/cancel moved to the bottom row
- Price +/- controls in the sell GUI; sell-from-GUI emerald button on the main auction house
- Confirm purchase GUI layout refresh with balance display
- Fixed sell-menu close/reopen duplication and unreachable confirm/cancel
- Credit: @LeoArs06 for the SellGui slot-collision + close-handling fix ([PR #4](https://github.com/MeherBenSalem/DonutAuction/pull/4))

## 1.2.2

- Economy bridge detection supports VaultUnlocked as well as Vault
- Soft-disables cleanly when no Economy provider is registered
- Fixed Folia / VaultUnlocked servers failing to load with “Vault isn’t on the server”
- Removed hard `depend: Vault`

## 1.2.0

- Permission-based auction limits (`donutauction.limit.*`) and `/ah limit`
- Slot expansions (`donutauction.slots.*`)
- Fast buy / fast sell preferences
- Marketplace QoL (filters, lore mode, shulker preview, update checker)

See git history for earlier changes.
