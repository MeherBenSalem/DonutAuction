# DonutAuctionHouse Patch Notes

## 1.3.0 — Sell GUI fixes & marketplace QoL

- Sell GUI expanded to 45 slots; confirm/cancel moved to bottom row
- Price +/- controls in sell GUI; sell-from-GUI emerald button on main auction house
- Confirm purchase GUI layout refresh with balance display
- Fixed sell-menu close/reopen duplication and unreachable confirm/cancel
- **Credit:** @LeoArs06 for the SellGui slot-collision + close-handling fix ([PR #4](https://github.com/MeherBenSalem/DonutAuction/pull/4)) — apologies for shipping 1.3.0 without merging/crediting that PR initially

See `DonutAuctionHouse-1.3.0-PatchNotes.md` for full release notes.

## 1.2.0 — Quality of Life & Marketplace Expansion Update

### Feature 1 — Permission-Based Auction Limits

Configurable permission-based auction limits with highest-permission-wins resolution.

**New permissions:**
- `donutauction.limit.5` (default: true)
- `donutauction.limit.10`
- `donutauction.limit.25`
- `donutauction.limit.50`
- `donutauction.limit.100`
- `donutauction.limit.unlimited` (-1)

**New command:** `/ah limit` — displays your current limit, active listings, and remaining slots.

**Config:**
```yaml
auction-limits:
  enabled: true
  default-limit: 5
  permissions:
    donutauction.limit.10: 10
    donutauction.limit.25: 25
    donutauction.limit.50: 50
    donutauction.limit.100: 100
    donutauction.limit.unlimited: -1
```

### Feature 2 — Preserve Original Item Lore

New configurable lore system that preserves original item lore from custom item plugins.

**Lore modes:**
- `APPEND` — Adds auction info below existing lore (default)
- `REPLACE` — Replaces lore entirely (legacy behavior)
- `DISABLED` — Does not modify lore at all

**Config:**
```yaml
auction-lore:
  mode: APPEND
  separator: true
```

**Compatible with:** MMOItems, Oraxen, ItemsAdder, EcoItems, ExecutableItems, MythicMobs, and any custom item plugin using ItemMeta/NBT.

### Feature 3 — Fast Buy System

Instant purchase mode that skips the confirmation GUI.

**New permission:** `donutauction.fastbuy`

**New command:** `/ah fastbuy` — toggles fast buy mode.

**Config:**
```yaml
fast-buy:
  enabled: true
  default-state: false
  require-permission: true
```

**Behavior:**
- Normal: Click item → Confirm Purchase → Purchase Complete
- Fast Buy: Click item → Purchase Complete
- Setting persists across reconnects and server restarts.

### Feature 4 — Fast Sell System

Instant listing mode that uses stored settings.

**New permission:** `donutauction.fastsell`

**New command:** `/ah fastsell` — toggles fast sell mode.

**Config:**
```yaml
fast-sell:
  enabled: true
  default-state: false
  require-permission: true
```

**Behavior:**
- Normal: `/ah sell <price>` → Sell GUI for duration/category selection
- Fast Sell: `/ah sell <price>` → Listed immediately using last settings
- Automatically remembers last duration, category, and price.

### Feature 5 — Shulker Box Support

Full shulker box support with content preview.

**Config:**
```yaml
shulker-support:
  enabled: true
  preview-contents: true
```

**Features:**
- Preserves all contents, NBT, names, lore, enchantments, colors, and custom metadata.
- Right-click any shulker box in the auction to preview its contents.
- Read-only preview GUI prevents extraction while listed.
- No duplication exploits.

### Feature 6 — Modrinth Update Checker

Modern update checker using the Modrinth API.

**Config:**
```yaml
update-checker:
  enabled: true
  notify-console: true
  notify-admins: true
  check-interval-hours: 12
```

**New permission:** `donutauction.update.notify`

**Behavior:**
- Checks asynchronously, never blocks startup.
- Caches results, handles rate limits and API downtime.
- Notifies console on enable if update available.
- Notifies admins on join if update available.

### Feature 7 — Auction Slot Expansion

Additional monetization-friendly slot permissions.

**New permissions:**
- `donutauction.slots.10`
- `donutauction.slots.25`
- `donutauction.slots.50`
- `donutauction.slots.100`

**Config:**
```yaml
auction-slots:
  enabled: true
  permissions:
    donutauction.slots.10: 10
    donutauction.slots.25: 25
    donutauction.slots.50: 50
    donutauction.slots.100: 100
```

### Performance Improvements

- All database operations now use the plugin's named async executor instead of the default ForkJoinPool.
- Operation lock cleanup prevents memory leaks from accumulated auction locks.
- Fire-and-forget DB updates now include exception logging.
- Search performance optimized with cached plain-text names.

### Security & Stability Fixes

- **CRITICAL:** Fixed item duplication exploit where server crash during purchase could duplicate items.
- **CRITICAL:** Fixed item duplication exploit where server crash during cancellation could duplicate items.
- **CRITICAL:** Fixed item duplication exploit where server crash during item collection could duplicate items.
- All inventory/economy actions now happen **after** database persistence succeeds.
- Failed purchases now refund money automatically.
- Failed cancellations now restore auction state automatically.
- Failed collections now restore auction state automatically.

### New Permissions Summary

| Permission | Description |
|---|---|
| `donutauction.use` | Access to the auction house |
| `donutauction.sell` | Allows selling items |
| `donutauction.admin` | Administrative controls |
| `donutauction.fastbuy` | Toggle fast buy mode |
| `donutauction.fastsell` | Toggle fast sell mode |
| `donutauction.update.notify` | Update notifications |
| `donutauction.limit.5` | 5 listing limit |
| `donutauction.limit.10` | 10 listing limit |
| `donutauction.limit.25` | 25 listing limit |
| `donutauction.limit.50` | 50 listing limit |
| `donutauction.limit.100` | 100 listing limit |
| `donutauction.limit.unlimited` | Unlimited listings |
| `donutauction.slots.10` | 10 slot expansion |
| `donutauction.slots.25` | 25 slot expansion |
| `donutauction.slots.50` | 50 slot expansion |
| `donutauction.slots.100` | 100 slot expansion |

### New Commands Summary

| Command | Description |
|---|---|
| `/ah limit` | View your auction limit and remaining slots |
| `/ah fastbuy` | Toggle fast buy mode |
| `/ah fastsell` | Toggle fast sell mode |

### Migration Notes from 1.1.0 → 1.2.0

- Existing configs are safely updated with new defaults on startup.
- A new `player_preferences` table is created automatically for fast buy/sell settings.
- Existing `donutcore.*` permissions continue to work alongside new `donutauction.*` permissions.
- Existing auction data is fully compatible — no data migration required.
- The lore system defaults to `APPEND` mode. Set to `REPLACE` to preserve 1.1.0 behavior.
