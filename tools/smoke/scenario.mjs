// The flow every server in the matrix runs. Slots match the default GUI layouts in the plugin.
// Smoke config (run.mjs): 10% sales tax paid to "Bank", expiry scan every 5 s, new-listing broadcasts on.
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function waitWindow(bot, trigger, timeout = 15_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('windowOpen', onOpen);
      reject(new Error(`${bot.username}: no window opened`));
    }, timeout);
    const onOpen = (window) => {
      clearTimeout(timer);
      // Let the server finish sending the contents.
      setTimeout(() => resolve(window), 700);
    };
    bot.once('windowOpen', onOpen);
    trigger();
  });
}

async function waitChat(bot, pattern, timeout = 15_000) {
  const start = Date.now();
  let seen = 0;
  while (Date.now() - start < timeout) {
    for (; seen < bot.chatLog.length; seen++) {
      if (pattern.test(bot.chatLog[seen])) {
        const line = bot.chatLog[seen];
        bot.chatLog.splice(0, seen + 1);
        return line;
      }
    }
    await sleep(100);
  }
  throw new Error(`${bot.username}: no chat matching ${pattern} (saw: ${bot.chatLog.slice(-5).join(' | ')})`);
}

function slotOf(window, name, from = 0, to = 44) {
  for (let i = from; i <= to; i++) if (window.slots[i]?.name === name) return i;
  return -1;
}

const number = (line) => Number(line.trim().split(' ').pop());
const near = (a, b) => Math.abs(a - b) < 0.001;

/** Runs one named check; a failure is recorded and the scenario continues where it can. */
async function check(step, label, body) {
  try {
    const detail = await body();
    step(label, detail !== false, typeof detail === 'string' ? detail : '');
    return detail !== false;
  } catch (error) {
    step(label, false, error.message);
    return false;
  }
}

export async function scenario({ server, target, joinBot, step }) {
  step('plugin enabled', server.lines.some((l) => /DonutAuctionHouse enabled using/.test(l)));
  await check(step, 'public API registered', async () => (await server.lab('api')).replace(/.*\[LAB\] /, ''));
  step('PlaceholderAPI hooked', server.lines.some((l) => /placeholders registered/.test(l)));

  await server.lab('money Buyer 5000');
  const seller = await joinBot('Seller');
  const buyer = await joinBot('Buyer');
  step('bots joined', true, `${target.bot}`);

  try {
    // Buy It Now with 10% sales tax.
    await server.lab('give Seller DIAMOND 3');
    await sleep(500);
    const listed = await check(step, 'sell GUI lists at /ah sell 250', async () => {
      const sell = await waitWindow(seller, () => seller.chat('/ah sell 250'));
      if (sell.slots[4]?.name !== 'diamond') throw new Error(`slot 4 is ${sell.slots[4]?.name}`);
      await seller.clickWindow(40, 0, 0);
      await waitChat(seller, /Listed/);
      return 'listed';
    });
    if (listed) {
      await check(step, 'buyer finds and buys it', async () => {
        const browse = await waitWindow(buyer, () => buyer.chat('/ah'));
        const slot = slotOf(browse, 'diamond');
        if (slot < 0) throw new Error('diamond not in browser');
        const confirm = await waitWindow(buyer, () => buyer.clickWindow(slot, 0, 0));
        if (confirm.slots[22]?.name !== 'diamond') throw new Error('confirm GUI missing item');
        await buyer.clickWindow(30, 0, 0);
        await waitChat(buyer, /Purchased/);
        return `slot ${slot}`;
      });
      await check(step, 'seller told about the sale', async () => (await waitChat(seller, /sold to Buyer/)).slice(0, 60));
      await sleep(500);
      await check(step, 'buyer received items', async () => number(await server.lab('count Buyer DIAMOND')) === 3);
      await check(step, 'buyer charged 250', async () => near(number(await server.lab('balance Buyer')), 4750));
      await check(step, 'seller paid 225 after tax', async () => {
        const value = number(await server.lab('balance Seller'));
        return near(value, 225) ? '225' : (() => { throw new Error(`seller has ${value}`); })();
      });
      await check(step, 'tax account paid 25', async () => near(number(await server.lab('balance Bank')), 25));
    }

    // An auction created through the public API, bid on through the bid menu, won and auto-delivered.
    await check(step, 'API auction + bid + win', async () => {
      await server.lab('auction Seller GOLD_INGOT 5 100 65', /\[LAB\] (auction|error) .*/);
      await sleep(500);
      const browse = await waitWindow(buyer, () => buyer.chat('/ah'));
      const slot = slotOf(browse, 'gold_ingot');
      if (slot < 0) throw new Error('auction not in browser');
      const bid = await waitWindow(buyer, () => buyer.clickWindow(slot, 0, 0));
      if (bid.slots[13]?.name !== 'gold_ingot') throw new Error('bid menu missing item');
      await buyer.clickWindow(29, 0, 0);
      await waitChat(buyer, /You bid/);
      const afterBid = number(await server.lab('balance Buyer'));
      if (!near(afterBid, 4650)) throw new Error(`bid not held: buyer has ${afterBid}`);
      buyer.closeWindow(buyer.currentWindow);
      await waitChat(buyer, /You won/, 120_000);
      await sleep(1500);
      const gold = number(await server.lab('count Buyer GOLD_INGOT'));
      if (gold !== 5) throw new Error(`winner has ${gold} gold`);
      const sellerBalance = number(await server.lab('balance Seller'));
      if (!near(sellerBalance, listed ? 315 : 90)) throw new Error(`seller has ${sellerBalance}`);
      return 'won, delivered, paid';
    });

    await check(step, 'history menu opens', async () => {
      const history = await waitWindow(buyer, () => buyer.chat('/ah history'));
      return history.slots.length >= 54 ? 'ok' : false;
    });
    await check(step, '/ah price answers', async () => {
      await server.lab('give Buyer DIAMOND 1');
      await sleep(300);
      buyer.chat('/ah price');
      return (await waitChat(buyer, /Market price/)).slice(0, 50);
    });
    await check(step, 'console /ah admin info', async () => {
      server.mark();
      server.command('ah admin info');
      return (await server.waitFor(/Active listings: \d+/, 10_000))[0];
    });
    await check(step, 'PlaceholderAPI value', async () => {
      server.mark();
      server.command('papi parse Seller %donutauction_limit%|%donutauction_total_listings%');
      return (await server.waitFor(/^.*\b5\|\d+\s*$/, 10_000))[0].replace(/.*INFO\]: /, '');
    });
  } finally {
    seller.quit();
    buyer.quit();
    await sleep(1000);
  }
}
