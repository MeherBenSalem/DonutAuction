# Browsing and buying

`/ah` (or `/auction`) opens a 45-slot market. Hover any listing to see its price, seller and time left, and click to buy.

![Browsing the market and buying an item](../marketing/images/gifs/buy.gif)

## The market

![The market, with a listing's enchantments, price, seller and time left](images/browse-hover.jpg)

- **Search** with `/ah search <text>` or the search button: item names, lore, enchantments (`sharpness`), seller names and the contents of shulker boxes. Every word must match.
- **One seller's shop** with `/ah view <player>`, or just `/ah <player>`.
- Item names are shown in each player's own language.

## Sorting

![The five sort orders](images/browse-sort.jpg)

Click the sort button to cycle through **Recently Listed**, **Ending Soon**, **Lowest Price**, **Highest Price** and **Lowest Price Each**. Stacks show their price per item, so a stack of 64 can be compared with a single item.

## Filters

![Category and listing-type filters](images/filter-menu.jpg)

Filter by category (blocks, tools, food, combat, potions, books, ingredients, utilities) and by listing type: everything, Buy It Now only or auctions only.

## Shulker boxes

![A shulker box listing showing its contents in the tooltip](images/browse-shulker-hover.jpg)

Shulker box listings list their contents in the tooltip. **Right-click** to open a full preview before you buy:

![Previewing a shulker box's contents](images/shulker-preview.jpg)

Turn this off with `shulker-support.preview-contents: false`.

## Buying

![Confirm a purchase, with your balance afterwards](images/confirm-purchase.jpg)

Clicking a Buy It Now listing opens a confirmation with the price and your balance after paying. The item goes into your inventory if the whole stack fits; otherwise it waits safely in your [collection](collection-and-history.md).

**Fast buy:** `/ah fastbuy` toggles skipping the confirmation (permission `donutauction.fastbuy`, configured under `fast-buy`).

Auction listings work differently: clicking one opens the bid menu. See [Auctions and bids](auctions.md).
