# Marketing assets: 2.0 "The Market Update"

Listing copy and artwork for CurseForge, Modrinth and Hangar. Nothing here ships in the plugin jar.

## Listing

| File | Use |
| --- | --- |
| [`curseforge/description.md`](curseforge/description.md) | Full project description (Markdown; also works on Modrinth and Hangar) |
| [`curseforge/summary.txt`](curseforge/summary.txt) | Short project summary (238 characters) |
| [`../DonutAuctionHouse-2.0.0-PatchNotes.md`](../DonutAuctionHouse-2.0.0-PatchNotes.md) | File changelog used by `scripts/publish-release.mjs` |

Image links in the description point at `raw.githubusercontent.com/MeherBenSalem/DonutAuction/main/marketing/images/...`, so they work once this folder is on `main`. To host on CurseForge instead, upload the images to the project gallery and replace each URL.

The description leads with the market, then selling, auctions, price insight, notifications, the collection, economy and rules, safety, integrations, staff tools, configuration, compatibility, commands and permissions, the FAQ and a gallery of 2.0.0 screenshots.

## Adding a Minecraft version

The supported range comes from [`release/supported-minecraft.json`](../release/supported-minecraft.json), which the release tooling also uses. The banner and the compatibility card read it, so a new version needs no artwork edits:

1. Add the version to `release/supported-minecraft.json` and to `MATRIX` in [`tools/smoke/run.mjs`](../tools/smoke/run.mjs).
2. Run the smoke matrix, then `node tools/smoke/compat.mjs` to refresh `source/compat.json`.
3. Rerun the renderer for `banner compatibility`, and add a row to the compatibility table in `curseforge/description.md` if the Java requirement changes.

A new release name or number goes in `RELEASE` in `source/render.cjs` and in the first lines of the description.

## Images

IMAGES_TABLE

### How the screenshots were captured

Every screenshot and the GIF are unedited captures of the **vanilla Minecraft 1.21.11 client** (launched with portablemc) on a **Paper 1.21.11** server running DonutAuctionHouse 2.0.0. The client ran in a 1920×1080 Xvfb display with software rendering (Mesa llvmpipe) at GUI scale 3, and was driven by [`tools/marketing/capture.mjs`](../tools/marketing/capture.mjs) through console commands and XTEST mouse and keyboard input.

- **The world** is seed 8675309 on peaceful, at noon, in the nearest cherry grove. `scenery.jpg` (and the blurred card backdrop) has the HUD hidden with F1. Every menu shot has the HUD on.
- **The market** was filled through the plugin's own API by the smoke-test fixture (`/lab seed 44`): made-up sellers, prices around typical survival values, some enchanted and named items and a "Starter Kit" shulker box. The player Moonbeam listed four items of their own. The sale history behind price insight was written straight into the SQLite database before the second start (nine diamond, netherite and elytra sales in the last day).
- **Configuration:** defaults, except a 5% sales tax paid to a `ServerBank` account and new-listing broadcasts on. The economy is the fixture's in-memory Vault economy, so prices use Vault's plain `$` formatting.
- **Real actions:** every purchase, bid and sale in the shots happened. A second player, Kiri (a mineflayer bot), placed the bid shown on the auction and bought Moonbeam's diamonds for the "Item sold" title and chat shots. The staff shots were taken after `/op Moonbeam`.
- **`buy.gif`** is a real-time recording (x11grab, 12 fps, cropped to the centre 1280×720 and scaled to 640×360) of opening the market, hovering a few listings and buying ender pearls.

## Regenerating the artwork

The cards, banner, icon and headers are HTML rendered with Playwright from the screenshots above, the bundled fonts and files in this repository:

```bash
NODE_PATH="$(npm root -g)" node marketing/source/render.cjs            # everything
NODE_PATH="$(npm root -g)" node marketing/source/render.cjs banner icon  # selected assets
```

- `source/backdrop.jpg` is the blurred background scene, a 1280×720 copy of `screenshots/scenery.jpg`.
- `source/compat.json` is the result of the last cross-version run (`node tools/smoke/compat.mjs`). The compatibility card shows every target in it.
- `source/console/admin-info.txt` is the server console output of `/ah admin info`, saved by the capture script.
- The economy and configuration cards print the current `economy` section of `config.yml` and the first `notify` lines of `messages.yml`, so they follow the defaults.
- The Discord card is a preview of the embed that `DiscordWebhook` builds (title, item, enchantments, Price, Seller, Buyer, Type, footer), drawn in Discord's dark theme. The trade shown is an example.
- `source/fonts/` holds Outfit and Inter with their SIL Open Font License 1.1 texts. The script uses only these files.

To refresh the screenshots, build the plugin and the smoke fixture, then run the capture script (it needs Xvfb, ImageMagick, ffmpeg, sqlite3, python-xlib and portablemc; see the header of `capture.mjs`):

```bash
mvn -B -DskipTests package
mvn -B -q -f tools/smoke/fixture/pom.xml package -Ddonutauction.version=2.0.0 -Ddonutauction.jar="$PWD/target/DonutAuctionHouse-2.0.0.jar"
AUCTIONLAB_PORT=25600 node tools/marketing/capture.mjs
```

Card crops are pixel regions of the 1920×1080 captures (`CROP` in `source/assets.cjs`), so new captures may need their crops and label positions adjusted.

## Wording

Say what the plugin does, in plain words, with numbers that are 2.0.0 defaults from `config.yml` or the code. Do not use other servers', plugins' or games' names, logos or art in listing images or titles, and do not compare against named competitors. The made-up player names in the screenshots (Moonbeam, Kiri, Willow, Draxen and the rest) are not real players.
