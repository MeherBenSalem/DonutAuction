# DonutAuctionHouse v1.2.1

### New Features
* None

### Improvements
* Sell confirmation now uses atomic pending-sale transactions with unique IDs so an item is owned by only one state at a time (inventory, pending sale, or active auction)
* Confirm, cancel, inventory close, disconnect, and shutdown recovery are idempotent against double-clicks, lag, and repeated callbacks
* Cancel and collect actions on existing auctions use operation locks to prevent double item returns
* Failed listing validation now safely restores the item (with ground drop if the inventory is full)
* Added sampled debug logging for invalid/repeated pending-sale claims (`debug.pending-sales`)

### Bug Fixes
* Fixed item duplication when cancelling `/ah sell` confirmation (Cancel, GUI close, duration/category re-open, double-click, or disconnect could return the item more than once)
* Pending sell items are now returned correctly on player quit and server shutdown

### Configuration
* `debug.pending-sales` (default: `false`) — when `true`, logs every invalid/repeated pending-sale claim; when `false`, samples only

### Compatibility
* Drop-in update from 1.2.0
* No database migration required
* No command or permission changes
