// The listing artwork. Each entry returns { html, width, height, transparent?, file? } for render.cjs.
// Crops are pixel boxes [x, y, w, h] of the 1920x1080 captures in images/screenshots/ (GUI scale 3).
const r = require('./render.cjs');

const HEADERS = {
  market: 'The Market', selling: 'Selling', auctions: 'Auctions', insight: 'Price Insight', notifications: 'Notifications',
  collection: 'Collection', economy: 'Economy & Rules', safety: 'Built to Be Safe', integrations: 'Integrations',
  staff: 'Staff Tools', config: 'Configuration', compatibility: 'Compatibility', commands: 'Commands & Permissions',
  faq: 'Questions', gallery: 'Gallery',
};

// Crop boxes [x, y, w, h] in the 1920x1080 captures: the chest part of each menu (title and rows, not the player's
// own inventory) joined with the box of the hovered slot's tooltip, plus a 14 px margin. Tooltip boxes were measured
// from the captures (see marketing/README.md), so new captures may need new boxes.
const CROP = {
  'admin-browse': [682, 193, 691, 435],
  'bid-menu': [682, 220, 790, 531],
  'browse-auction-hover': [682, 193, 790, 413],
  'browse-hover': [682, 193, 556, 465],
  'sell-fees': [682, 220, 778, 369],
  'sell-insight': [682, 220, 811, 459],
  'sell-type': [682, 220, 715, 361],
  'shulker-preview': [682, 193, 586, 413],
  'your-items-summary': [682, 193, 820, 477],
  'your-items': [682, 193, 622, 413],
  'price-check': [0, 826, 1000, 142],     // chat: the /ah price answer
  'sold-title': [540, 390, 840, 250],     // the ITEM SOLD title
  'sold-scene': [360, 260, 1200, 620],    // the ITEM SOLD title over the world (from sold-title.jpg)
  'sold-chat-line': [0, 903, 990, 64],    // the chat message about the sale (from sold-chat.jpg)
};

/** Width and height of a crop scaled to fit maxW x maxH. */
const fitted = (name, maxW, maxH) => {
  const box = CROP[name];
  const width = Math.round(Math.min(maxW, maxH * box[2] / box[3]));
  return [width, Math.round(width * box[3] / box[2])];
};
const fileOf = (name) => (name === 'sold-scene' ? 'sold-title' : name === 'sold-chat-line' ? 'sold-chat' : name) + '.jpg';

/** A framed crop of a capture, as wide as possible within maxW x maxH. */
const shotArt = (name, maxW, maxH, style = '') => r.framed(r.region(fileOf(name), ...CROP[name], fitted(name, maxW, maxH)[0]), style);

// The card art area is 850 x 780. main() sits top-left, inset() bottom-right; each label is pinned to its frame's corner.
const ART_W = 850;
const ART_H = 780;
const main = (name, maxW, maxH, top, text, kind = '') => {
  return `${shotArt(name, maxW, maxH, `position:absolute;left:0;top:${top}px`)}${text ? r.label(text, `left:24px;top:${top - 22}px`, kind) : ''}`;
};
const inset = (name, maxW, maxH, bottom, text, kind = 'gold') => {
  const [w, h] = fitted(name, maxW, maxH);
  const left = ART_W + 20 - w;
  const top = ART_H - bottom - h;
  // Thin strips (chat lines) get the label above the frame, so it covers no text.
  const labelTop = h < 200 ? top - 50 : top - 22;
  return `${shotArt(name, maxW, maxH, `position:absolute;left:${left}px;top:${top}px`)}${text ? r.label(text, `left:${left + 24}px;top:${labelTop}px`, kind) : ''}`;
};

const card = (key, options) => () => ({ width: 1600, height: 900, file: `card-${key}.png`, html: r.card(options) });

const assets = {
  icon: () => ({ width: 512, height: 512, html: r.page(512, 512, `
    <div style="position:absolute;inset:0;background:radial-gradient(circle at 50% 42%,#2d1f3a 0%,#160f1e 58%,#0b0810 100%)"></div>
    ${r.sprinkles(26, 0.55)}
    <div style="position:absolute;left:46px;top:46px;width:420px;height:420px;border-radius:50%;
      background:radial-gradient(circle,rgba(255,111,174,.30) 0%,rgba(255,111,174,0) 66%)"></div>
    <div style="position:absolute;left:64px;top:56px;filter:drop-shadow(0 18px 26px rgba(0,0,0,.6))">${r.donutSvg(384)}</div>
    <div style="position:absolute;right:42px;bottom:40px;display:flex;align-items:center;gap:10px;padding:12px 22px 11px 18px;border-radius:999px;
      background:linear-gradient(180deg,#ffd76a,#f0a92e);box-shadow:0 10px 26px rgba(0,0,0,.55),inset 0 -3px 0 rgba(120,70,0,.35)">
      <span style="font-family:DaDisplay;font-weight:900;font-size:58px;line-height:1;color:#3b2203">$</span>
      <span style="font-family:DaDisplay;font-weight:900;font-size:40px;line-height:1;color:#3b2203;letter-spacing:.02em">AH</span></div>`) }),

  divider: () => ({ width: 1400, height: 40, transparent: true, html: r.divider() }),

  banner: () => ({ width: 1800, height: 600, html: r.page(1800, 600, `
    ${r.backdrop(0.5, 6)}
    ${r.sprinkles(60, 0.4)}
    <div style="position:absolute;left:870px;top:-40px;width:1120px;height:700px;perspective:1700px">
      <div style="transform:rotateY(-17deg) rotateX(4deg) translateZ(0);transform-origin:left center;margin-top:62px">
        ${shotArt('browse-auction-hover', 860, 500)}
      </div>
    </div>
    <div style="position:absolute;inset:0;background:linear-gradient(90deg,rgba(14,10,20,.95) 0%,rgba(14,10,20,.86) 40%,rgba(14,10,20,.15) 62%,rgba(14,10,20,0) 100%)"></div>
    <div style="position:absolute;left:90px;top:0;bottom:0;width:820px;display:flex;flex-direction:column;justify-content:center;z-index:2">
      <div class="kicker"><span class="dot"></span>${r.RELEASE.version} · ${r.RELEASE.name}</div>
      <div style="display:flex;align-items:center;gap:26px;margin:22px 0 6px">
        <div style="width:118px;height:118px;filter:drop-shadow(0 12px 20px rgba(0,0,0,.6))">${r.donutSvg(118)}</div>
        <div class="display" style="font-size:96px;line-height:.92">Donut<br><span class="grad">Auction House</span></div>
      </div>
      <div class="rule" style="width:72%;margin:26px 0 24px"></div>
      <div style="font-size:31px;line-height:1.35;color:var(--text)">Buy it now or bid. Instant sale alerts, price insight<br>and a market nobody can dupe. <b class="hi">Free.</b></div>
      <div style="margin-top:30px;display:flex;align-items:center">
        <span class="pill"><i></i>Paper</span><span class="pill"><i></i>Folia</span><span class="pill"><i></i>Purpur</span>
        <span style="font-family:DaDisplay;font-weight:700;font-size:26px;color:var(--gold-hi);margin-left:12px">${r.RANGE}</span>
      </div>
    </div>`) }),

  market: card('market', {
    kicker: 'The Market', title: 'The whole market in one menu',
    points: [
      '<b class="hi">Search</b> names, lore, enchantments, sellers and shulker contents',
      '<b class="hi">Five sorts</b>, including the lowest price each',
      'Category and Buy It Now or Auction filters',
      'Right-click a shulker box to look inside',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('browse-hover', 600, 520, 40, 'Every listing, every detail')}
      ${inset('shulker-preview', 380, 268, 0, 'Shulker preview')}
    </div>`,
  }),

  selling: card('selling', {
    kicker: 'Selling', title: 'Sell in seconds',
    points: [
      '<code class="inline">/ah sell 2.5k</code>: k, m, b and t all work',
      'Type an exact price, or shift-click to step faster',
      'Pick a duration and Buy It Now or Auction',
      'See your tax and earnings before you list',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('sell-type', 800, 400, 40, 'Buy It Now or Auction')}
      ${inset('sell-fees', 520, 250, 0, 'Fees &amp; earnings')}
    </div>`,
  }),

  auctions: card('auctions', {
    kicker: 'Auctions', title: 'Real auctions, done right',
    points: [
      'Bids are held in <b class="hi">escrow</b>, and outbid players are refunded at once',
      '<b class="hi">Anti-snipe</b>: late bids extend the auction',
      'Bid the minimum, +10%, +25% or any amount',
      'Settled exactly once, even across servers',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('browse-auction-hover', 760, 400, 40, 'A live bid')}
      ${inset('bid-menu', 430, 290, 0, 'The bid menu')}
    </div>`,
  }),

  insight: card('insight', {
    kicker: 'Price Insight', title: 'Know what it is worth',
    points: [
      'Average, range and last price each from recent sales',
      'The cheapest listing on sale right now',
      '<code class="inline">/ah price</code> for whatever you hold',
      'PlaceholderAPI values for scoreboards and holograms',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('sell-insight', 800, 460, 40, 'In the sell menu')}
      ${inset('price-check', 700, 160, 20, '/ah price')}
    </div>`,
  }),

  notifications: card('notifications', {
    kicker: 'Notifications', title: 'Never miss a sale',
    points: [
      'A chat message, sound and title the moment it sells',
      'A summary of sales made while you were away',
      'Outbid, won, expired and staff notices',
      'Follows you to whichever server you are on',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('sold-scene', 830, 500, 70, 'The moment it sells')}
      ${inset('sold-chat-line', 780, 60, 60, 'And in chat')}
    </div>`,
  }),

  collection: card('collection', {
    kicker: 'Collection', title: 'Nothing is ever lost',
    points: [
      'Items move only as whole stacks that fit',
      'Inventory full? It waits in <code class="inline">/ah collect</code>',
      'Right-click to relist, or Collect All at once',
      'Your listings, limit and totals at a glance',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('your-items', 640, 430, 40, 'Expired? Reclaim or relist')}
      ${inset('your-items-summary', 480, 280, 0, 'Your trading')}
    </div>`,
  }),

  economy: card('economy', {
    kicker: 'Economy & Rules', title: 'Your economy, your rules',
    points: [
      'Sales tax with permission tiers and a tax account',
      'Listing fees: flat, a percentage, or both, with a cap',
      'Minimum and maximum prices per item',
      'Blacklist, disabled worlds, game modes and a cooldown',
    ],
    art: `<div style="position:relative;height:780px">
      <div style="position:absolute;left:0;top:10px">${r.codePanel('config.yml (defaults)', r.yaml(r.yamlSection('src/main/resources/config.yml', 'economy').filter((line) => !line.trim().startsWith('#'))), { width: 580, size: 18 })}</div>
      ${inset('sell-fees', 560, 270, 30, 'What the seller sees')}
    </div>`,
  }),

  safety: card('safety', {
    kicker: 'Built to Be Safe', title: 'Dupe-safe by design',
    points: [
      'Every buy, bid and reclaim is a claim only one player can win',
      'Items are never dropped and never in two places',
      'Folia-safe: inventories change on the player’s own thread',
      'Stored in Paper’s versioned item format, so listings survive updates',
    ],
    art: (() => {
      const player = (name, server, color, result, ok) => `<div class="panel" style="padding:20px 24px;width:330px">
        <div style="font-family:DaDisplay;font-weight:700;font-size:15px;letter-spacing:.16em;text-transform:uppercase;color:var(--dim)">${server}</div>
        <div style="font-family:DaDisplay;font-weight:800;font-size:30px;color:${color};margin:4px 0 10px">${name} clicks Buy</div>
        <div style="font-size:20px;color:${ok ? 'var(--mint)' : 'var(--red)'};font-weight:600">${result}</div></div>`;
      const sql = `<span style="color:var(--pink-hi)">UPDATE</span> auctions <span style="color:var(--pink-hi)">SET</span> status = <span style="color:var(--gold-hi)">'SOLD'</span>, buyer_uuid = ?
 <span style="color:var(--pink-hi)">WHERE</span> auction_id = ?
   <span style="color:var(--pink-hi)">AND</span> status = <span style="color:var(--gold-hi)">'ACTIVE'</span>
   <span style="color:var(--pink-hi)">AND</span> expiration_time &gt; ?`;
      return `<div style="position:relative;height:760px;display:flex;flex-direction:column;justify-content:center;gap:26px">
        <div style="display:flex;justify-content:space-between">
          ${player('Kiri', 'Survival 1', 'var(--hi)', '1 row updated → charged, gets the item', true)}
          ${player('Draxen', 'Survival 2', 'var(--hi)', '0 rows → “already sold”, nothing charged', false)}
        </div>
        <div style="display:flex;justify-content:space-around;font-family:DaDisplay;font-weight:800;font-size:34px;color:var(--pink)"><span>↓</span><span>↓</span></div>
        ${r.codePanel('The same row, one winner (MySQL or SQLite)', sql, { width: 740, size: 21 })}
        ${r.stats([['Winners per item', '1'], ['Items dropped', '0'], ['Servers', 'Any number']])}
      </div>`;
    })(),
  }),

  integrations: card('integrations', {
    kicker: 'Integrations', title: 'Plays well with others',
    points: [
      'Discord webhooks for listings, sales, bids and wins',
      '<b class="hi">PlaceholderAPI</b>: 14 placeholders, including price insight',
      'A developer API with cancellable events',
      'Vault and VaultUnlocked economies',
    ],
    art: (() => {
      const field = (name, value) => `<div style="min-width:150px"><div style="font-weight:700;font-size:16px;color:#f2f3f5">${name}</div><div style="font-size:16px;color:#dbdee1">${value}</div></div>`;
      // A preview of the embed the webhook sends (fields and layout from DiscordWebhook.json).
      const embed = `<div style="width:560px;border-radius:10px;background:#2b2d31;padding:22px 20px 14px;border-left:5px solid #4cc38a;font-family:DaText;box-shadow:0 26px 70px rgba(0,0,0,.6)">
        <div style="font-size:15px;color:#dbdee1;font-weight:600;margin-bottom:6px">Willow</div>
        <div style="font-size:18px;color:#f2f3f5;font-weight:700;margin-bottom:6px">Sold</div>
        <div style="font-size:16px;color:#dbdee1;line-height:1.45;margin-bottom:12px"><b style="color:#f2f3f5">Dawnbreaker</b><br>Sharpness V<br>Looting III<br>Unbreaking III<br>Mending</div>
        <div style="display:flex;gap:18px;flex-wrap:wrap;margin-bottom:12px">${field('Price', '$11,240.00')}${field('Seller', 'Willow')}${field('Buyer', 'Kiri')}${field('Type', 'Buy It Now')}</div>
        <div style="font-size:13px;color:#949ba4">DonutAuctionHouse</div></div>`;
      const papi = ['%donutauction_active%', '%donutauction_collect%', '%donutauction_earned%', '%donutauction_average_DIAMOND%', '%donutauction_lowest_ELYTRA%']
        .map((p) => `<span style="color:var(--gold-hi)">${p}</span>`).join('\n');
      const api = `<span style="color:var(--pink-hi)">DonutAuctionApi</span>.get().ifPresent(api -&gt;
    api.createListing(seller, <span style="color:var(--gold-hi)">"Crates"</span>, item, <span style="color:var(--sky)">5000</span>,
        Duration.ofHours(<span style="color:var(--sky)">24</span>), ListingType.BIN));`;
      return `<div style="position:relative;height:780px">
        <div style="position:absolute;left:0;top:34px">${embed}</div>
        ${r.label('Discord webhook', 'left:24px;top:8px', 'mint')}
        <div style="position:absolute;right:-10px;top:330px">${r.codePanel('PlaceholderAPI', papi, { width: 440, size: 18 })}</div>
        <div style="position:absolute;left:0;bottom:0">${r.codePanel('Developer API', api, { width: 700, size: 18 })}</div>
      </div>`;
    })(),
  }),

  staff: card('staff', {
    kicker: 'Staff Tools', title: 'Moderate the market',
    points: [
      '<b class="hi">Shift-right-click</b> any listing to remove it',
      'The item goes back to its seller, and any bid is refunded',
      '<code class="inline">/ah admin view</code>, <code class="inline">remove</code>, <code class="inline">info</code> and <code class="inline">purge</code>',
      'A daily log of every listing, sale and bid',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('admin-browse', 700, 440, 40, 'Staff view')}
      <div style="position:absolute;right:-20px;bottom:0">${r.codePanel('Console · /ah admin info', r.consoleText('admin-info.txt').map(r.escapeHtml).join('\n'), { width: 700, size: 15 })}</div>
    </div>`,
  }),

  config: card('config', {
    kicker: 'Configuration', title: 'Make it yours',
    points: [
      'Every word in <code class="inline">messages.yml</code>, menus included',
      'MiniMessage, &amp; codes and hex colors, mixed freely',
      'Player-made names can never inject formatting',
      'New settings arrive on update; yours are kept',
    ],
    art: `<div style="position:relative;height:780px">
      <div style="position:absolute;left:0;top:10px">${r.codePanel('messages.yml', r.yaml(r.yamlSection('src/main/resources/messages.yml', 'notify').slice(0, 5)), { width: 820, size: 18, wrap: true })}</div>
      ${inset('sold-title', 620, 210, 30, 'In game')}
    </div>`,
  }),

  compatibility: card('compatibility', {
    kicker: 'Compatibility', title: 'Tested on every version',
    points: [
      'Paper, Folia, Purpur and other Paper forks',
      `Minecraft ${r.RANGE}, one jar`,
      'Real clients list, buy, bid and win on each server',
      'Vault or VaultUnlocked, plus any economy plugin',
    ],
    art: (() => {
      const data = r.compat();
      const targets = data ? data.targets : [];
      const tile = (t) => `<div class="panel" style="padding:14px 18px 12px;border-color:${t.ok ? 'rgba(73,214,160,.45)' : 'rgba(255,107,107,.6)'}">
        <div style="font-family:DaDisplay;font-weight:700;font-size:14px;letter-spacing:.16em;text-transform:uppercase;color:var(--dim)">${t.project}</div>
        <div style="font-family:DaDisplay;font-weight:800;font-size:31px;color:var(--hi);line-height:1.1">${t.version}</div>
        <div style="font-size:16px;color:${t.ok ? 'var(--mint)' : 'var(--red)'};font-weight:600;margin-top:3px">${t.ok ? '✔' : '✖'} ${t.passed}/${t.steps} checks · Java ${t.java}</div></div>`;
      return `<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:16px;width:780px">${targets.map(tile).join('')}</div>
        <div style="margin-top:18px;font-size:18px;color:var(--dim)">DonutAuctionHouse ${data ? data.plugin : ''} · automated run of tools/smoke on ${data ? data.date : ''}</div>`;
    })(),
  }),
};

for (const [key, title] of Object.entries(HEADERS)) {
  assets[`header-${key}`] = () => ({ width: 1400, height: 120, transparent: true, html: r.header(title) });
}
module.exports = assets;
