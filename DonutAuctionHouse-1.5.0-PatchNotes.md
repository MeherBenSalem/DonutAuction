# DonutAuctionHouse v1.5.0

### New Features
* Optional Redis listing notifications for multi-server networks (`sync.redis` in `config.yml`, off by default).
* MySQL poll fallback so sold listings disappear on other nodes even without Redis.

### Improvements
* Purchase, cancel, and expire now claim the database row before changing local cache.
* Vault withdraw/deposit for a claimed purchase runs on the buyer’s Folia entity thread.

### Bug Fixes
* Buying an auction on one Folia server while MySQL is shared no longer leaves the listing buyable on another server.

### Configuration
* `sync.poll-interval-seconds` (default `3`, MySQL only; `0` disables).
* `sync.redis.enabled`, `host`, `port`, `password`, `channel`.

### Compatibility
* Paper and Folia, Minecraft 1.20.1 through 26.2, Java 17.
* SQLite single-server behavior is unchanged when Redis is disabled.

### Upgrade Notes
1. Replace the jar. Keep existing `plugins/DonutAuctionHouse/` data.
2. For a network: set `storage.type: MYSQL` on every node to the same database.
3. Optional: set `sync.redis.enabled: true` on every node with the same `channel`.
4. Restart each node. Confirm a buy on server B removes the listing from `/ah` on server A and a second buy is refused.
