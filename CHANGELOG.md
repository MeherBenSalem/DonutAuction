# Changelog

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
