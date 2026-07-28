# DonutAuctionHouse v1.2.2

### New Features
* None

### Improvements
* Economy bridge detection now supports **VaultUnlocked** in addition to classic **Vault**
* Clearer startup logs when a Vault-compatible Economy service is linked or missing
* Soft-disables cleanly when no Economy provider is registered instead of throwing on enable

### Bug Fixes
* Fixed Folia / VaultUnlocked servers failing to load DonutAuctionHouse with “Vault isn’t on the server”
* Removed hard `depend: Vault` so the plugin can enable when only VaultUnlocked is installed

### Configuration
* None

### Compatibility
* Drop-in update from 1.2.1
* Requires Vault **or** VaultUnlocked plus an economy plugin that registers Vault’s Economy service
* Folia-supported remains enabled
* No database migration required
* No command or permission changes

### Upgrade Notes
1. Install `DonutAuctionHouse-1.2.2.jar` (replace 1.2.1)
2. Keep VaultUnlocked (or Vault) + your economy plugin enabled
3. Restart the server and confirm console shows: `Economy linked via ...`
4. If it still disables, paste the new Economy startup lines from the console
