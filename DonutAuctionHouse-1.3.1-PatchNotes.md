# DonutAuctionHouse v1.3.1

### New Features
* None

### Improvements
* Full `messages.yml` translation support for GUI titles, button labels, lore, chat messages, command text, and status labels
* `/ah reload` now reloads `messages.yml` alongside `config.yml`
* Prefix and minimum-price messages can live in `messages.yml` (legacy `config.yml` keys still work)

### Bug Fixes
* None

### Configuration
* New `messages.yml` — edit and `/ah reload` to translate the plugin without recompiling
* Existing `config.yml` `messages.prefix` and `messages.price-below-min` remain supported as fallbacks

### Compatibility
* Drop-in update from 1.3.0
* Folia-supported unchanged
* No database migration required
* No command or permission changes

### Upgrade Notes
1. Replace the jar with `DonutAuctionHouse-1.3.1.jar`
2. Restart the server (or `/ah reload` after first start to generate `messages.yml`)
3. Edit `plugins/DonutAuctionHouse/messages.yml` to translate player-facing text
