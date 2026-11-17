const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const script = readFileSync(process.env.PRODUCTS_SCRIPT || resolve(__dirname,
  '../../main/resources/META-INF/resources/products/products.js'), 'utf8');

class Element {
  constructor() { this.children = []; this.listeners = {}; this.disabled = false; this.value = ''; this.textContent = ''; }
  append(...children) { this.children.push(...children.flatMap(child => child.fragment ? child.children : [child])); }
  replaceChildren(...children) { this.children = []; this.append(...children); }
  setAttribute(name, value) { this[name] = value; }
  addEventListener(name, listener) { this.listeners[name] = listener; }
  dispatch(name) { return this.listeners[name]?.(); }
}

const token = (region, direction, id) => `p1.${region}.25.${direction}.${id}`;
function page(region, number, count = 3) {
  return {
    region,
    items: [{ productId: String(9007199254740990n + BigInt(number)), sellerId: '9223372036854775807',
      name: region + ' product ' + number, sellerName: '<b>Seller</b>', region,
      countryCode: "FR", newOrderEligible: true, legacyReason: null,
      initialStock: 10, stock: 7, price: '99999999999999999.99', currency: 'EUR',
      profitsEarned: { EUR: '-100000000000000000.01' } }],
    nextAfter: number < count ? String(number * 25) : null,
    nextCursor: number < count ? token(region, 'a', number * 25) : null,
    previousCursor: number > 1 ? token(region, 'b', (number - 1) * 25 + 1) : null
  };
}
const response = data => ({ ok: true, json: async () => data });
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

function browser(url = 'https://shop.example/products/', handler = query => page(query.get('region'),
  query.has('last') ? 3 : query.has('after') ? Number(query.get('after').split('.').at(-1)) / 25 + 1 : query.has('before') ? (Number(query.get('before').split('.').at(-1)) - 1) / 25 : 1)) {
  const elements = new Map();
  const element = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  element('region').value = 'US';
  const entries = [url];
  let historyIndex = 0;
  const location = { href: url };
  const listeners = {};
  const calls = [];
  const timers = new Map();
  let timerId = 0;
  const history = {
    pushState(_, __, path) { entries.splice(++historyIndex); entries.push(new URL(path, location.href).href); location.href = entries[historyIndex]; },
    replaceState(_, __, path) { entries[historyIndex] = new URL(path, location.href).href; location.href = entries[historyIndex]; }
  };
  const context = {
    document: { getElementById: element, createElement: () => new Element(),
      createDocumentFragment: () => Object.assign(new Element(), { fragment: true }) },
    window: { location, history, addEventListener: (name, listener) => { listeners[name] = listener; } },
    URL, URLSearchParams, AbortController, BigInt,
    setTimeout(callback, ms) { const id = ++timerId; timers.set(id, { callback, ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
    async fetch(path, options) { const query = new URL(path, url).searchParams; calls.push({ query, signal: options.signal });
      const result = await handler(query, options.signal); return result?.ok === false ? result : response(result); }
  };
  vm.runInNewContext(script, context, { filename: 'products.js' });
  return {
    element, calls, timers, entries,
    get url() { return location.href; },
    click(id) { if (!element(id).disabled) element(id).dispatch('click'); },
    region(value) { element('region').value = value; element('region').dispatch('change'); },
    back() { assert.ok(historyIndex > 0); location.href = entries[--historyIndex]; listeners.popstate(); },
    forward() { assert.ok(historyIndex < entries.length - 1); location.href = entries[++historyIndex]; listeners.popstate(); },
    expire() { for (const timer of [...timers.values()]) timer.callback(); },
    async settled() { for (let turn = 0; turn < 10; turn++) await new Promise(setImmediate); }
  };
}
const paths = app => app.calls.map(call => call.query.get('after'));
const text = element => element.textContent + element.children.map(text).join('');

// Restoring a position must perform one direct request, regardless of depth.
test('copied cursor URL, reload and Refresh fetch only the selected position', async () => {
  const url = 'https://shop.example/products/?view=compact&region=EU&after=' + token('EU', 'a', 50) + '#catalog';
  const app = browser(url); await app.settled();
  assert.equal(app.calls.length, 1);
  assert.match(text(app.element('products')), /EU product 3/);
  assert.equal(app.element('page-label').textContent, 'Catalog page');
  assert.equal(app.url, url);
  app.click('refresh'); await app.settled();
  assert.equal(app.calls.length, 2);
  assert.equal(app.calls[1].query.get('after'), token('EU', 'a', 50));
  assert.equal(app.entries.length, 1);
  const reloaded = browser(app.url); await reloaded.settled();
  assert.equal(reloaded.calls.length, 1);
  assert.match(text(reloaded.element('products')), /EU product 3/);
});

test('Next Previous First and history each request one durable position', async () => {
  const app = browser(); await app.settled();
  app.click('next'); await app.settled();
  assert.equal(new URL(app.url).searchParams.get('after'), token('US', 'a', 25));
  app.click('previous'); await app.settled();
  assert.equal(app.calls.at(-1).query.get('before'), token('US', 'b', 26));
  assert.match(text(app.element('products')), /US product 1/);
  app.back(); await app.settled();
  assert.match(text(app.element('products')), /US product 2/);
  app.forward(); await app.settled();
  assert.match(text(app.element('products')), /US product 1/);
  assert.equal(app.calls.length, 5);
  assert.equal(app.entries.length, 3);
});

test('default and legacy page 1 canonicalize without dropping unrelated filters or fragment', async () => {
  const app = browser('https://shop.example/products/?view=compact&page=1#catalog'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?view=compact&region=US#catalog');
  assert.equal(app.calls.length, 1);
  assert.equal(app.element('first').disabled, true);
});

test('deep numbered links require explicit migration without any traversal', async () => {
  for (const number of ['2', '99', '999999999999999999999999999999999']) {
    const app = browser('https://shop.example/products/?region=EU&page=' + number); await app.settled();
    assert.equal(app.calls.length, 0);
    assert.match(app.element('state-description').textContent, /Numbered links/);
    assert.equal(app.element('first').disabled, false);
    assert.equal(app.element('last').disabled, false);
    app.click('last'); await app.settled();
    assert.equal(app.calls.length, 1);
    assert.equal(app.calls[0].query.get('last'), 'true');
    assert.equal(new URL(app.url).searchParams.has('page'), false);
  }
});

test('invalid and conflicting cursor or region URL parameters surface a recoverable link error', async () => {
  for (const query of ['region=unknown', 'after=', 'after=bad%20cursor', 'after=0',
    'last=false', 'after=x&before=y', 'region=US&region=EU', 'last=true&page=3',
    'after=' + 'x'.repeat(600), 'before=9223372036854775808']) {
    const app = browser('https://shop.example/products/?' + query); await app.settled();
    assert.equal(app.calls.length, 0, query);
    assert.match(app.element('state-title').textContent, /Couldn't open catalog link/);
    assert.equal(app.element('previous').disabled, true);
    app.click('first'); await app.settled();
    assert.equal(app.calls.length, 1);
  }
});

test('region changes reset cursors and browser history restores region and cursor directly', async () => {
  const app = browser('https://shop.example/products/?region=EU&after=' + token('EU', 'a', 50)); await app.settled();
  app.region('ASIA'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=ASIA');
  app.back(); await app.settled();
  assert.equal(app.element('region').value, 'EU');
  assert.equal(app.calls.at(-1).query.get('after'), token('EU', 'a', 50));
  app.forward(); await app.settled();
  assert.equal(app.calls.length, 4);
});

function catalog(size, query) {
  const region = query.get('region');
  const anchor = Number((query.get('after') || query.get('before') || '.0').split('.').at(-1));
  let ids = Array.from({ length: size }, (_, index) => index + 1);
  if (query.has('after')) ids = ids.filter(id => id > anchor).slice(0, 25);
  else if (query.has('before')) ids = ids.filter(id => id < anchor).slice(-25);
  else ids = query.has('last') ? ids.slice(-25) : ids.slice(0, 25);
  const result = page(region, 1, 1);
  result.items = ids.map(id => ({ ...result.items[0], productId: String(id), name: 'Product ' + id }));
  const first = ids[0], last = ids.at(-1);
  result.previousCursor = first > 1 ? token(region, 'b', first) : query.has('after') && !ids.length ? token(region, 'b', anchor + 1) : null;
  result.nextCursor = last < size ? token(region, 'a', last) : null;
  return result;
}

test('0 1 25 26 50 51 products preserve direct Last and bidirectional boundaries', async t => {
  for (const size of [0, 1, 25, 26, 50, 51]) await t.test(String(size), async () => {
    const app = browser(undefined, query => catalog(size, query)); await app.settled();
    assert.equal(app.element('products').children.length, Math.min(size, 25));
    assert.equal(app.element('first').disabled, true);
    assert.equal(app.element('next').disabled, size <= 25);
    if (size <= 25) { assert.equal(app.element('last').disabled, true); return; }
    app.click('last'); await app.settled();
    assert.equal(app.calls.length, 2);
    assert.equal(app.calls[1].query.get('last'), 'true');
    const products = app.element('products').children.map(text);
    assert.match(products[0], new RegExp('Product ' + (size - 24)));
    assert.match(products.at(-1), new RegExp('Product ' + size));
    assert.equal(app.element('last').disabled, true);
    assert.equal(app.element('previous').disabled, false);
    assert.equal(app.element('page-label').textContent, 'Last page');
    app.click('refresh'); await app.settled();
    assert.equal(app.calls.length, 3);
    assert.equal(app.calls[2].query.get('last'), 'true');
    const copied = browser(app.url, query => catalog(size, query)); await copied.settled();
    assert.equal(copied.calls.length, 1);
    assert.equal(copied.element('products').children.length, 25);
    app.click('previous'); await app.settled();
    assert.equal(app.calls.length, 4);
    assert.equal(app.calls[3].query.has('before'), true);
    app.click('first'); await app.settled();
    assert.equal(app.calls.length, size > 50 ? 5 : 4);
  });
});

test('an emptied cursor view offers First and Last recovery without hidden reads', async () => {
  const app = browser('https://shop.example/products/?region=EU&after=' + token('EU', 'a', 50), query => {
    const result = catalog(25, query);
    if (!result.items.length) result.previousCursor = null;
    return result;
  });
  await app.settled();
  assert.equal(app.calls.length, 1);
  assert.equal(app.element('state-title').textContent, 'No products found');
  assert.equal(app.element('previous').disabled, true);
  assert.equal(app.element('next').disabled, true);
  assert.equal(app.element('first').disabled, false);
  assert.equal(app.element('last').disabled, false);
  app.click('first'); await app.settled();
  assert.equal(app.calls.length, 2);
  assert.equal(app.element('products').children.length, 25);
});

test('deadline aborts one direct request; Refresh retries the same position', async () => {
  const pending = deferred(); let stalled = true;
  const app = browser('https://shop.example/products/?region=EU&last=true', query => stalled ? pending.promise : page('EU', 3));
  await app.settled();
  assert.equal(app.calls.length, 1);
  assert.equal([...app.timers.values()][0].ms, 8000);
  app.expire(); pending.resolve(page('EU', 3)); await app.settled();
  assert.equal(app.calls[0].signal.aborted, true);
  assert.match(app.element('state-description').textContent, /took too long/);
  for (const id of ['first', 'previous', 'next', 'last']) assert.equal(app.element(id).disabled, true);
  assert.equal(app.timers.size, 0);
  stalled = false; app.click('refresh'); await app.settled();
  assert.equal(app.calls.length, 2);
  assert.equal(app.calls[1].query.get('last'), 'true');
});

test('superseded Last response cannot overwrite a new region', async () => {
  const pending = deferred();
  const app = browser(undefined, query => query.has('last') ? pending.promise : page(query.get('region'), 1));
  await app.settled(); app.click('last'); await app.settled();
  app.region('ASIA'); await app.settled();
  pending.resolve(page('US', 3)); await app.settled();
  assert.equal(app.calls[1].signal.aborted, true);
  assert.match(text(app.element('products')), /ASIA product 1/);
  assert.equal(app.element('first').disabled, true);
});

test('failed and malformed responses render no partial page and Refresh retries', async () => {
  for (const failure of ['network', 'shape']) {
    let failing = true;
    const app = browser(undefined, () => {
      if (failing && failure === 'network') throw new Error('unavailable');
      const result = page('US', 1); if (failing) result.nextCursor = 'invalid cursor'; return result;
    });
    await app.settled();
    assert.equal(app.element('state-title').textContent, "Couldn't load products");
    assert.equal(app.element('products').children.length, 0);
    failing = false; app.click('refresh'); await app.settled();
    assert.match(text(app.element('products')), /US product 1/);
    assert.equal(app.calls.length, 2);
  }
});

test('product identities, exact decimal money, and names remain text', async () => {
  const app = browser('https://shop.example/products/?region=EU&after=' + token('EU', 'a', 50));
  await app.settled();
  const content = text(app.element('products'));
  assert.match(content, /9007199254740993/);
  assert.match(content, /9223372036854775807/);
  assert.match(content, /99,999,999,999,999,999\.99/);
  assert.match(content, /−100,000,000,000,000,000\.01/);
  assert.match(content, /<b>Seller<\/b>/);
});


test('country, legacy reason and currency minor units are visible without losing historical money', async () => {
  const app = browser('https://shop.example/products/?region=ASIA', () => {
    const result = page('ASIA', 1, 1);
    result.items = ['JPY', 'KRW', 'VND', 'GBP'].map((currency, index) => ({ ...result.items[0],
      productId: String(index + 1), countryCode: index === 3 ? null : 'JP', currency,
      price: index === 1 ? '19.01' : '19.00', profitsEarned: { [currency]: index === 1 ? '-1.01' : '-1.00' },
      newOrderEligible: index === 0, legacyReason: index === 0 ? null : index === 3 ?
        'UNRESOLVED_SELLER_COUNTRY' : 'INVALID_CURRENCY_PRECISION' }));
    return result;
  });
  await app.settled();
  const rendered = app.element('products').children.map(text);
  assert.match(rendered[0], /19JPY/);
  assert.match(rendered[0], /−1JPY/);
  assert.match(rendered[1], /19\.01KRW/);
  assert.match(rendered[1], /−1\.01KRW/);
  assert.match(rendered[2], /19VND/);
  assert.match(rendered[3], /19\.00GBP/);
  assert.match(rendered[3], /Country unresolved/);
  assert.match(rendered[3], /Unavailable for new orders: UNRESOLVED_SELLER_COUNTRY/);
});


test('server rejects region or filter bound cursor with a visible recoverable error', async () => {
  const app = browser('https://shop.example/products/?region=ASIA&after=' + token('EU', 'a', 50), () => ({ ok: false, status: 400 }));
  await app.settled();
  assert.equal(app.calls.length, 1);
  assert.match(app.element('state-description').textContent, /invalid cursor.*selected region/);
  assert.equal(app.element('first').disabled, false);
  assert.equal(app.element('last').disabled, false);
});


test('Back during a pending Next restores directly and discards the late response', async () => {
  const pending = deferred();
  const app = browser(undefined, query => query.has('after') ? pending.promise : page('US', 1));
  await app.settled(); app.click('next');
  assert.equal(app.element('next').disabled, true);
  app.back(); await app.settled();
  pending.resolve(page('US', 2)); await app.settled();
  assert.equal(app.calls.length, 3);
  assert.equal(app.calls[1].signal.aborted, true);
  assert.match(text(app.element('products')), /US product 1/);
  assert.equal(app.url, 'https://shop.example/products/?region=US');
});

test('a copied Previous cursor reloads directly and Last Refresh sees appended products', async () => {
  let size = 51;
  const app = browser(undefined, query => catalog(size, query)); await app.settled();
  app.click('last'); await app.settled();
  size = 60; app.click('refresh'); await app.settled();
  assert.match(text(app.element('products').children.at(-1)), /Product 60/);
  assert.equal(app.calls.length, 3);
  app.click('previous'); await app.settled();
  const copied = browser(app.url, query => catalog(size, query)); await copied.settled();
  assert.equal(copied.calls.length, 1);
  assert.equal(copied.calls[0].query.get('before'), token('US', 'b', 36));
  assert.equal(text(copied.element('products')), text(app.element('products')));
});


test('response cursors must match the region direction and fixed page size', async () => {
  for (const [field, value] of [
    ['nextCursor', token('EU', 'a', 25)],
    ['nextCursor', token('US', 'b', 25)],
    ['previousCursor', token('US', 'a', 25)],
    ['nextCursor', 'p1.US.50.a.25'],
    ['previousCursor', 'p1.US.25.b.9223372036854775808']
  ]) {
    const app = browser(undefined, () => ({ ...page('US', 1), [field]: value }));
    await app.settled();
    assert.equal(app.calls.length, 1);
    assert.equal(app.element('state-title').textContent, "Couldn't load products");
    assert.match(app.element('state-description').textContent, /unexpected response/);
    assert.equal(app.element('products').children.length, 0);
    for (const id of ['first', 'previous', 'next', 'last']) assert.equal(app.element(id).disabled, true);
  }
});
