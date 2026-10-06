# DonutAuctionHouse v1.6.0

## Features

- Optional compact k/M browse and Your Items prices. Disabled by default; existing economy formatting and config values are preserved. Configure suffixes, 0-6 fractional digits, locale, and currency template. Display rounding never changes stored prices or payments.
- Buy/sell confirmations show the provider's formatted price and every decimal digit of the amount.
- A book in the auction browser opens private, read-only Bought/Sold history. Includes previous/next pages, Refresh, Back, item, precise price, date/time zone, and seller/buyer. Unknown names fall back to UUIDs; unavailable item metadata uses a placeholder.
- History queries enforce viewer ownership in the service/database path and run asynchronously. Stale results cannot reopen a menu after navigation or disconnect.

## Fixes

- Reject non-finite prices such as `NaN`, and reject out-of-range sell command prices before removing the held item. Invalid prices are no longer silently clamped into a different listing price.
- Database index initialization uses portable metadata checks rather than MySQL-unsupported `CREATE INDEX IF NOT EXISTS` syntax. Migration errors are reported instead of being hidden.

## Upgrade and data

- Replace the jar and restart. Keep existing config, messages and database files; back up the database before upgrades. New settings/default messages are added without resetting custom values.
- History reuses the existing `auctions` table and adds one `purchase_completed` flag. Legacy SOLD records remain visible; new pre-payment claims and failed purchases are excluded. No parallel ledger or payment mechanism is introduced.
- Completion bookkeeping runs after existing payment/item delivery and cannot undo settlement. If the database fails at that point, a record may be absent; the server logs its auction ID. Legacy versions cannot distinguish old crash-interrupted SOLD claims from completed sales.

## Verification and compatibility

- Java 17 bytecode and Paper 1.20.1 API target retained; JDK 21 builds supported.
- Store support matrix retained: Paper, Folia, Purpur, Spigot and Bukkit, Minecraft 1.20.1 through 26.3.
- Unit, registered Bukkit command/GUI event, native SQLite, history privacy/pagination/migration, and settlement regressions are included. CI also checks Java 17/21 and native MySQL.
- Isolated headless Paper 1.20.1 and 26.3 probes passed startup, item serialization, formatting and database history. Real-client visual checks, every intermediate server/version, and multi-server Folia/Vault tests have not been run. Folia's official download API did not provide a 26.3 build during verification.
- The customer's `/ah sell <price>` browser-opening report has no proven customer-specific cause. A foreign `/ah` alias collision reproduces the symptom; the namespaced `/donutauctionhouse:ah sell <price>` routes correctly in tests. This release does not claim that unresolved report is fixed. Please provide plugin/server versions, exact command, held item, namespaced comparison and logs.
