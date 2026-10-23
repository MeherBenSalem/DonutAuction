// Captures the listing screenshots from a real game: a Paper server running DonutAuctionHouse and the
// unmodified vanilla Minecraft client (software rendering under Xvfb), driven through console commands
// and XTEST mouse/keyboard input. A mineflayer bot plays the second trader.
//
// Needs: Xvfb, ImageMagick (import), ffmpeg, python3 with python-xlib, portablemc, and the smoke-test
// cache (tools/smoke). Run from the repository root after `mvn package` and the fixture build:
//   AUCTIONLAB_PORT=25600 node tools/marketing/capture.mjs
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Server, joinBot } from '../smoke/run.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const lab = process.env.AUCTIONLAB_HOME || path.join(os.homedir(), '.cache', 'donutauction-smoke');
const PORT = Number(process.env.AUCTIONLAB_PORT || 25600);
const VERSION = process.env.CAPTURE_VERSION || '1.21.11';
const PLAYER = 'Moonbeam';
const DISPLAY = ':99';
const W = 1920;
const H = 1080;
const SCALE = 3;
const shots = path.join(repo, 'marketing', 'images', 'screenshots');
const gifs = path.join(repo, 'marketing', 'images', 'gifs');
const python = process.env.CAPTURE_PYTHON || 'python3';
const portablemc = process.env.PORTABLEMC || 'portablemc';
const clientDir = process.env.CAPTURE_CLIENT_DIR || path.join(lab, 'client');

// Noon, clear sky, no command echo in chat. 1.21.11 renamed the game rules (advance_time, ...), so both
// spellings are sent; the server rejects the one it does not know.
const WORLD_SETUP = ['time set 6000', 'weather clear',
  'gamerule advance_time false', 'gamerule advance_weather false', 'gamerule show_advancement_messages false', 'gamerule send_command_feedback false',
  'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false', 'gamerule announceAdvancements false', 'gamerule sendCommandFeedback false'];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const x = (...args) => execFileSync(python, [path.join(here, 'xinput.py'), ...args.map(String)], { env: { ...process.env, DISPLAY } });

/** Pixel centre of a container slot in a chest menu with the given number of rows (GUI scale 3). */
function slot(index, rows) {
  const left = Math.floor((W / SCALE - 176) / 2);
  const top = Math.floor((H / SCALE - (114 + rows * 18)) / 2);
  return [(left + 8 + (index % 9) * 18 + 8) * SCALE, (top + 18 + Math.floor(index / 9) * 18 + 8) * SCALE];
}

function screenshot(name, quality = 92) {
  fs.mkdirSync(shots, { recursive: true });
  const file = path.join(shots, `${name}.jpg`);
  execFileSync('import', ['-display', DISPLAY, '-window', 'root', '-quality', String(quality), file]);
  console.log(`  captured ${path.relative(repo, file)}`);
  return file;
}

async function hover(index, rows, settle = 700) {
  const [px, py] = slot(index, rows);
  x('move', px - 9, py - 9);
  await sleep(120);
  x('move', px, py);
  await sleep(settle);
}

async function clickSlot(index, rows, settle = 1200) {
  const [px, py] = slot(index, rows);
  x('click', px, py);
  await sleep(settle);
}

/** Records the screen for `seconds` (x11grab); returns a promise for the finished MP4. */
function record(name, seconds) {
  const raw = path.join(lab, 'capture', `${name}.mp4`);
  const proc = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'x11grab', '-video_size', `${W}x${H}`, '-framerate', '20', '-i', `${DISPLAY}.0`,
    '-t', String(seconds), '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '16', raw], { stdio: 'ignore' });
  return new Promise((resolve) => proc.on('exit', () => resolve(raw)));
}

/** A 640x360 GIF from a recording, cropped to the 1280x720 centre of the screen (the menu and its tooltips). */
function gif(raw, name, speed = 1) {
  fs.mkdirSync(gifs, { recursive: true });
  const file = path.join(gifs, `${name}.gif`);
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', raw, '-vf',
    `setpts=PTS/${speed},crop=1280:720:320:180,fps=12,scale=640:360:flags=lanczos,split[a][b];[a]palettegen=max_colors=160:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle`,
    file]);
  console.log(`  recorded ${path.relative(repo, file)}`);
}

function writeClientOptions() {
  fs.mkdirSync(clientDir, { recursive: true });
  fs.writeFileSync(path.join(clientDir, 'options.txt'), [
    'version:4671', 'guiScale:3', 'renderDistance:10', 'simulationDistance:5', 'maxFps:30', 'graphicsMode:1',
    'pauseOnLostFocus:false', 'narrator:0', 'tutorialStep:none', 'skipMultiplayerWarning:true', 'joinedFirstServer:true',
    'onboardAccessibility:false', 'lang:en_us', 'fov:0.0', 'bobView:false', 'chatScale:1.0', 'chatWidth:1.0',
    'soundCategory_master:0.0', 'darkMojangStudiosBackground:true', 'hideServerAddress:true', 'renderClouds:"fancy"',
    'entityShadows:true', 'particles:0', 'mipmapLevels:4', 'ao:true', 'menuBackgroundBlurriness:3',
  ].join('\n') + '\n');
}

function prepareServer() {
  const dir = path.join(lab, 'capture', 'server');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'plugins', 'DonutAuctionHouse'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), [
    'online-mode=false', 'white-list=false', 'enforce-secure-profile=false', `server-port=${PORT}`, 'level-seed=8675309',
    'difficulty=peaceful', 'spawn-monsters=false', 'view-distance=10', 'simulation-distance=5', 'spawn-protection=0',
    'motd=DonutAuctionHouse', 'gamemode=survival', 'allow-flight=true',
  ].join('\n') + '\n');
  const target = path.join(repo, 'target');
  const plugin = fs.readdirSync(target).find((f) => /^DonutAuctionHouse-.*\.jar$/.test(f));
  const jars = [path.join(target, plugin), path.join(repo, 'tools', 'smoke', 'fixture', 'target', 'AuctionLabFixture.jar'),
    path.join(lab, 'cache', 'plugins', 'Vault-1.7.3.jar'), path.join(lab, 'cache', 'plugins', 'PlaceholderAPI.jar')];
  for (const jar of jars) fs.copyFileSync(jar, path.join(dir, 'plugins', path.basename(jar)));
  fs.writeFileSync(path.join(dir, 'plugins', 'DonutAuctionHouse', 'config.yml'), [
    'economy:', '  sales-tax:', '    enabled: true', '    percent: 5', '    deposit-to: ServerBank',
    'broadcast:', '  new-listings: true', '  min-price: 0',
    'update-checker:', '  enabled: false', 'metrics:', '  enabled: false',
  ].join('\n') + '\n');
  return dir;
}

/** Completed diamond and netherite sales for price insight, written straight into the database. */
function seedHistory(dir) {
  const db = path.join(dir, 'plugins', 'DonutAuctionHouse', 'auctions.db');
  const now = Date.now();
  const rows = [];
  const sales = [['DIAMOND', 64, 6080], ['DIAMOND', 32, 3200], ['DIAMOND', 16, 1480], ['DIAMOND', 64, 5950], ['DIAMOND', 8, 840],
    ['DIAMOND', 48, 4510], ['NETHERITE_INGOT', 4, 48000], ['NETHERITE_INGOT', 1, 12500], ['ELYTRA', 1, 185000]];
  sales.forEach(([material, amount, price], i) => {
    const id = crypto.randomUUID();
    const sold = now - (i + 1) * 3_600_000;
    rows.push(`INSERT INTO auctions(auction_id, seller_uuid, buyer_uuid, item_data, price, listing_time, expiration_time, sold_time, status,`
      + ` seller_claimed, purchase_completed, updated_at, item_material, item_amount, listing_type, seller_name, buyer_name, buyer_claimed)`
      + ` VALUES ('${id}', '${crypto.randomUUID()}', '${crypto.randomUUID()}', 'history', ${price}, ${sold - 7_200_000}, ${sold + 86_400_000},`
      + ` ${sold}, 'SOLD', 1, 1, ${sold}, '${material}', ${amount}, 'BIN', 'Willow', 'Draxen', 1);`);
  });
  execFileSync('sqlite3', [db], { input: rows.join('\n') });
}

async function main() {
  const java = path.join(lab, 'cache', 'jdk', '21', 'bin', 'java');
  const dir = prepareServer();
  const server = new Server(dir, java, path.join(lab, 'cache', 'servers', `paper-${VERSION}.jar`));
  server.start();
  await server.waitFor(/Done \([0-9.,]+s\)!/, 300_000, 'server start');
  console.log('server up');
  for (const command of WORLD_SETUP) server.command(command);
  await server.lab('money Moonbeam 2500000');
  await server.lab('money Kiri 900000');
  // Moonbeam's own listings (two end within a minute, so they are waiting to be reclaimed later).
  await server.lab('list Moonbeam ELYTRA 1 175000 172800', /\[LAB\] (list|error) .*/);
  await server.lab('list Moonbeam NETHERITE_INGOT 8 96000 86400 bid', /\[LAB\] (list|error) .*/);
  await server.lab('list Moonbeam DIAMOND_BLOCK 32 27000 60', /\[LAB\] (list|error) .*/);
  await server.lab('list Moonbeam GOLDEN_APPLE 16 1900 60', /\[LAB\] (list|error) .*/);
  await sleep(300);
  await server.lab('seed 44', /\[LAB\] seed .*/);
  await sleep(1500);
  server.command('stop');
  await server.exited;
  seedHistory(dir);
  const restarted = new Server(dir, java, path.join(lab, 'cache', 'servers', `paper-${VERSION}.jar`));
  restarted.start();
  await restarted.waitFor(/Done \([0-9.,]+s\)!/, 300_000, 'server restart');
  for (const command of WORLD_SETUP) restarted.command(command);
  await restarted.lab('money Moonbeam 2500000');
  await restarted.lab('money Kiri 900000');

  const xvfb = spawn('Xvfb', [DISPLAY, '-screen', '0', `${W}x${H}x24`, '-nolisten', 'tcp'], { stdio: 'ignore' });
  await sleep(1500);
  writeClientOptions();
  const client = spawn(portablemc, ['--main-dir', clientDir, 'start', `mojang:${VERSION}`, '-u', PLAYER, '--join-server', '127.0.0.1',
    '--join-server-port', String(PORT), '--resolution', `${W}x${H}`, '--jvm', path.join(lab, 'cache', 'jdk', '21', 'bin', 'java'),
    '--jvm-arg=-Xmx3G'], { env: { ...process.env, DISPLAY, LIBGL_ALWAYS_SOFTWARE: '1', __GL_SYNC_TO_VBLANK: '0' }, stdio: ['ignore', 'pipe', 'pipe'] });
  const clientLog = fs.createWriteStream(path.join(lab, 'capture', 'client.log'));
  client.stdout.pipe(clientLog);
  client.stderr.pipe(clientLog);
  try {
    await restarted.waitFor(new RegExp(`${PLAYER} joined the game`), 400_000, 'client join');
    console.log('client joined');
    await scenes(restarted);
  } finally {
    client.kill('SIGTERM');
    await sleep(2000);
    await restarted.stop();
    xvfb.kill('SIGTERM');
  }
}

async function order(server) {
  const line = await server.lab('order', /\[LAB\] order .*/);
  return line.replace(/.*\[LAB\] order /, '').split(',').map((entry) => {
    const [material, type, enchanted, seller] = entry.split(':');
    return { material, type, enchanted: enchanted === 'E', seller };
  });
}

/** Runs an action on a bot and resolves with the window it opens. */
function openWindow(bot, action) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('no window opened')), 10_000);
    bot.once('windowOpen', (window) => { clearTimeout(timer); setTimeout(() => resolve(window), 900); });
    action();
  });
}

function find(list, predicate, fallback = 0) {
  const index = list.findIndex(predicate);
  return index < 0 ? fallback : index;
}

async function scenes(server) {
  // A view over a cherry grove: the backdrop of every menu.
  server.mark();
  server.command('locate biome minecraft:cherry_grove');
  const located = await server.waitFor(/cherry_grove is at \[(-?\d+), [^,]+, (-?\d+)\]/, 120_000, 'locate');
  const [cx, cz] = [Number(located[1]), Number(located[2])];
  server.command(`spreadplayers ${cx} ${cz} 0 1 false ${PLAYER}`);
  await sleep(1500);
  // A one-block perch five blocks up, out of view, so the camera looks over the grove.
  server.command(`execute as ${PLAYER} at @s run setblock ~ ~4 ~ minecraft:cherry_leaves[persistent=true]`);
  server.command(`execute as ${PLAYER} at @s run tp @s ~ ~5 ~ 135 14`);
  // Unlock recipes and the diamond advancement now, so their toasts are gone before the shots.
  server.command(`recipe give ${PLAYER} *`);
  server.command(`advancement grant ${PLAYER} only minecraft:story/mine_diamond`);
  await sleep(50_000); // terrain loads slowly with software rendering
  x('key', 'F1');
  await sleep(1500);
  screenshot('scenery');
  x('key', 'F1');
  await sleep(800);

  // Kiri bids on the first auction, so the browser and the bid menu show a live bid.
  const before = await order(server);
  const bidOn = find(before, (l) => l.type === 'BID' && l.seller !== PLAYER && l.seller !== 'Kiri', -1);
  if (bidOn >= 0) {
    const bidder = await joinBot('Kiri', VERSION);
    try {
      await openWindow(bidder, () => bidder.chat('/ah'));
      await openWindow(bidder, () => bidder.clickWindow(bidOn, 0, 0));
      await bidder.clickWindow(29, 0, 0);
      await sleep(1500);
    } finally {
      bidder.quit();
    }
    await sleep(1500);
  }

  const page = await order(server);
  const sword = find(page, (l) => l.material === 'DIAMOND_SWORD');
  const auction = find(page, (l) => l.type === 'BID' && l.seller !== PLAYER);
  const shulker = find(page, (l) => l.material === 'SHULKER_BOX');
  const cheap = find(page, (l) => l.material === 'GOLDEN_CARROT' && l.type === 'BIN');
  const pearls = find(page, (l) => l.material === 'ENDER_PEARL' && l.type === 'BIN');
  console.log(`  slots: sword ${sword}, auction ${auction}, shulker ${shulker}, carrots ${cheap}, pearls ${pearls}`);

  // The browser.
  await server.lab(`sudo ${PLAYER} ah`);
  await sleep(2500);
  await hover(sword, 6);
  screenshot('browse-hover');
  await hover(auction, 6);
  screenshot('browse-auction-hover');
  await hover(shulker, 6);
  screenshot('browse-shulker-hover');
  await hover(47, 6);
  screenshot('browse-sort');

  // Shulker preview.
  const [sx, sy] = slot(shulker, 6);
  x('click', sx, sy, 3);
  await sleep(1800);
  screenshot('shulker-preview');
  await clickSlot(49, 6, 1800);

  // Buy two things for real (history and the purchase message).
  await clickSlot(cheap, 6, 1800);
  await hover(30, 5);
  screenshot('confirm-purchase');
  await clickSlot(30, 5, 2500);
  screenshot('purchased');
  // The second purchase is recorded: browse, pick, confirm.
  x('key', 'Escape');
  await sleep(1200);
  const afterFirst = await order(server);
  const recording = record('buy', 11);
  await sleep(400);
  await server.lab(`sudo ${PLAYER} ah`);
  await sleep(1600);
  for (const index of [sword, auction, shulker]) await hover(index, 6, 900);
  await hover(find(afterFirst, (l) => l.material === 'ENDER_PEARL' && l.type === 'BIN', pearls), 6, 900);
  await clickSlot(find(afterFirst, (l) => l.material === 'ENDER_PEARL' && l.type === 'BIN', pearls), 6, 1500);
  await hover(30, 5, 900);
  await clickSlot(30, 5, 2500);
  gif(await recording, 'buy');

  // Bidding.
  const afterSecond = await order(server);
  await clickSlot(find(afterSecond, (l) => l.type === 'BID' && l.seller !== PLAYER, auction), 6, 1800);
  await hover(13, 5);
  screenshot('bid-menu');
  await clickSlot(29, 5, 2200);
  screenshot('bid-placed');
  x('key', 'Escape');
  await sleep(800);

  // The sell menu with price insight and fees.
  server.command(`execute as ${PLAYER} run item replace entity @s weapon.mainhand with diamond 64`);
  await sleep(500);
  await server.lab(`sudo ${PLAYER} ah sell 6k`);
  await sleep(3000);
  await hover(31, 5, 1200);
  screenshot('sell-insight');
  await hover(33, 5);
  screenshot('sell-fees');
  await hover(29, 5);
  screenshot('sell-type');
  await clickSlot(40, 5, 3000);
  await hover(0, 6);
  screenshot('your-items');
  const mine = await order(server);
  console.log(`  own listings now: ${mine.filter((l) => l.seller === PLAYER).length}`);
  await hover(51, 6);
  screenshot('your-items-summary');

  // Another player buys the diamonds: the seller hears about it right away.
  x('key', 'Escape');
  await sleep(1200);
  const kiri = await joinBot('Kiri', VERSION);
  try {
    await sleep(1500);
    const view = await openWindow(kiri, () => kiri.chat('/ah view Moonbeam'));
    let target = -1;
    for (let i = 0; i < 45; i++) if (view.slots[i]?.name === 'diamond') { target = i; break; }
    if (target >= 0) {
      await openWindow(kiri, () => kiri.clickWindow(target, 0, 0));
      await kiri.clickWindow(30, 0, 0);
      await sleep(1100);
      screenshot('sold-title');
      await sleep(2600);
      screenshot('sold-chat');
    } else {
      console.log('  Kiri could not find the diamonds');
    }
  } finally {
    kiri.quit();
  }

  // Price check, history and the filter menu.
  server.command(`execute as ${PLAYER} run item replace entity @s weapon.mainhand with diamond 32`);
  await sleep(500);
  await server.lab(`sudo ${PLAYER} ah price`);
  await sleep(2500);
  screenshot('price-check');
  await server.lab(`sudo ${PLAYER} ah history`);
  await sleep(3000);
  await hover(0, 6);
  screenshot('history');
  await server.lab(`sudo ${PLAYER} ah`);
  await sleep(2500);
  await clickSlot(48, 6, 1800);
  await hover(20, 3);
  screenshot('filter-menu');
  x('key', 'Escape');
  await sleep(800);

  // Staff tools: a seller's listings in admin mode, and the console report.
  server.command(`op ${PLAYER}`);
  await sleep(1000);
  await server.lab(`sudo ${PLAYER} ah admin view Willow`);
  await sleep(2500);
  await hover(0, 6);
  screenshot('admin-view');
  x('key', 'Escape');
  await sleep(800);
  await server.lab(`sudo ${PLAYER} ah`);
  await sleep(2500);
  await hover(sword, 6);
  screenshot('admin-browse');
  x('key', 'Escape');
  await sleep(800);
  server.mark();
  const from = server.lines.length;
  server.command('ah admin info');
  await sleep(2500);
  const report = server.lines.slice(from).map((line) => line.replace(/^\[[^\]]*INFO\]:? ?/, '')).filter((line) => line.trim());
  fs.mkdirSync(path.join(repo, 'marketing', 'source', 'console'), { recursive: true });
  fs.writeFileSync(path.join(repo, 'marketing', 'source', 'console', 'admin-info.txt'), report.join('\n') + '\n');
  console.log(`  console: ${report.length} lines`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
