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

function page(region, number, count = 3) {
  return {
    region,
    items: [{ productId: String(9007199254740990n + BigInt(number)), sellerId: '9223372036854775807',
      name: region + ' product ' + number, sellerName: '<b>Seller</b>', region,
      countryCode: "FR", newOrderEligible: true, legacyReason: null,
      initialStock: 10, stock: 7, price: '99999999999999999.99', currency: 'EUR',
      profitsEarned: { EUR: '-100000000000000000.01' } }],
    nextAfter: number < count ? String(number * 25) : null
  };
}
const response = data => ({ ok: true, json: async () => data });
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

function browser(url = 'https://shop.example/products/', handler = query => page(query.get('region'),
  query.has('after') ? Number(query.get('after')) / 25 + 1 : 1)) {
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
    URL, URLSearchParams, AbortController,
    setTimeout(callback, ms) { const id = ++timerId; timers.set(id, { callback, ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
    async fetch(path, options) { const query = new URL(path, url).searchParams; calls.push({ query, signal: options.signal });
      return response(await handler(query, options.signal)); }
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

// The copied URL is the observable regression: a separate browser must show page 3.
test('a copied regional page 3 URL restores that page in a new browser', async () => {
  const app = browser('https://shop.example/products/?region=EU&page=3');
  await app.settled();
  assert.equal(app.element('region').value, 'EU');
  assert.equal(app.element('page-label').textContent, 'Page 3');
  assert.match(text(app.element('products')), /EU product 3/);
  assert.deepEqual(paths(app), [null, '25', '50']);
  assert.equal(app.element('next').disabled, true);
  assert.equal(app.element('previous').disabled, false);
});

test('default state has a canonical URL and preserves unrelated parameters and the fragment', async () => {
  const app = browser('https://shop.example/products/?view=compact#catalog');
  await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?view=compact&region=US&page=1#catalog');
  assert.equal(app.entries.length, 1);
  assert.equal(app.element('page-label').textContent, 'Page 1');
  assert.equal(app.element('previous').disabled, true);
});

test('Previous and Next update shareable URLs and reuse known cursors', async () => {
  const app = browser();
  await app.settled();
  app.click('next'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=US&page=2');
  app.click('next'); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 3');
  app.click('previous'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=US&page=2');
  assert.deepEqual(paths(app), [null, '25', '50', '25']);
  assert.equal(app.entries.length, 4);
  app.back(); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 3');
  app.forward(); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 2');
  assert.equal(app.entries.length, 4);
});

test('region change resets page 1 and Back/Forward restore both region and page', async () => {
  const app = browser('https://shop.example/products/?region=EU&page=3');
  await app.settled();
  app.region('ASIA'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=ASIA&page=1');
  assert.match(text(app.element('products')), /ASIA product 1/);
  app.back(); await app.settled();
  assert.equal(app.element('region').value, 'EU');
  assert.equal(app.element('page-label').textContent, 'Page 3');
  assert.match(text(app.element('products')), /EU product 3/);
  app.forward(); await app.settled();
  assert.equal(app.element('region').value, 'ASIA');
  assert.equal(app.element('page-label').textContent, 'Page 1');
});

test('Refresh rebuilds cursors and refreshes the current page without adding history', async () => {
  let revision = 0;
  const app = browser('https://shop.example/products/?region=EU&page=3', query => {
    const result = page(query.get('region'), query.has('after') ? Number(query.get('after')) / 25 + 1 : 1);
    result.items[0].name += ' revision ' + revision;
    return result;
  });
  await app.settled();
  revision++;
  app.click('refresh'); await app.settled();
  assert.deepEqual(paths(app), [null, '25', '50', null, '25', '50']);
  assert.equal(app.element('page-label').textContent, 'Page 3');
  assert.match(text(app.element('products')), /EU product 3 revision 1/);
  assert.equal(app.entries.length, 1);
});

test('invalid page parameters normalize to page 1 before any cursor traversal', async t => {
  for (const value of ['0', '-1', '01', '1.5', '1e3', ' 2', 'NaN', '9007199254740992', '9'.repeat(400)]) {
    await t.test(value.slice(0, 30), async () => {
      const app = browser('https://shop.example/products/?region=EU&page=' + encodeURIComponent(value));
      await app.settled();
      assert.equal(app.url, 'https://shop.example/products/?region=EU&page=1');
      assert.equal(app.element('page-label').textContent, 'Page 1');
      assert.deepEqual(paths(app), [null]);
    });
  }
});

test('invalid region normalizes to the US first page', async () => {
  for (const invalid of ['UNKNOWN', 'us', '']) {
    const app = browser('https://shop.example/products/?region=' + invalid + '&page=3');
    await app.settled();
    assert.equal(app.url, 'https://shop.example/products/?region=US&page=1');
    assert.equal(app.element('region').value, 'US');
    assert.deepEqual(paths(app), [null]);
  }
});

test('a page beyond the catalog end canonicalizes to its last actual page', async () => {
  const app = browser('https://shop.example/products/?region=EU&page=99');
  await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=EU&page=3');
  assert.equal(app.element('page-label').textContent, 'Page 3');
  assert.match(text(app.element('products')), /EU product 3/);
  assert.deepEqual(paths(app), [null, '25', '50']);
  assert.equal(app.element('next').disabled, true);
});

test('empty pages after a continuation retain the last real page', async () => {
  const app = browser('https://shop.example/products/?region=EU&page=3', query => {
    if (query.has('after')) return { region: 'EU', items: [], nextAfter: null };
    return page('EU', 1);
  });
  await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=EU&page=1');
  assert.equal(app.element('page-label').textContent, 'Page 1');
  assert.match(text(app.element('products')), /EU product 1/);
  assert.equal(app.element('next').disabled, true);
});

test('an empty catalog canonicalizes any requested page to page 1', async () => {
  const app = browser('https://shop.example/products/?region=EU&page=3', () => ({ region: 'EU', items: [], nextAfter: null }));
  await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 1');
  assert.equal(app.element('state-title').textContent, 'No products found');
  assert.equal(app.url, 'https://shop.example/products/?region=EU&page=1');
  assert.equal(app.element('previous').disabled, true);
  assert.equal(app.element('next').disabled, true);
});

test('an emptied cached page searches backward rather than displaying a fictitious page', async () => {
  let emptied = false;
  const app = browser('https://shop.example/products/?region=EU&page=3', query => {
    const number = query.has('after') ? Number(query.get('after')) / 25 + 1 : 1;
    return emptied && number > 1 ? { region: 'EU', items: [], nextAfter: null } : page('EU', number);
  });
  await app.settled();
  emptied = true;
  app.click('previous'); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 1');
  assert.equal(app.url, 'https://shop.example/products/?region=EU&page=1');
  assert.equal(app.element('next').disabled, true);
  assert.deepEqual(paths(app), [null, '25', '50', '25', null]);
});

test('a superseded region response cannot overwrite a newer region', async () => {
  const old = deferred();
  const app = browser('https://shop.example/products/?region=EU&page=3', query =>
    query.get('region') === 'EU' ? old.promise : page('US', 1));
  app.region('US'); await app.settled();
  old.resolve(page('EU', 1)); await app.settled();
  assert.equal(app.calls[0].signal.aborted, true);
  assert.equal(app.element('region').value, 'US');
  assert.match(text(app.element('products')), /US product 1/);
  assert.equal(app.url, 'https://shop.example/products/?region=US&page=1');
  assert.equal(app.calls.length, 2);
});

test('Back during an in-flight Next load keeps the restored page', async () => {
  const pending = deferred();
  const app = browser(undefined, query => query.has('after') ? pending.promise : page('US', 1));
  await app.settled();
  app.click('next');
  assert.equal(app.element('next').disabled, true);
  app.back(); await app.settled();
  pending.resolve(page('US', 2)); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 1');
  assert.match(text(app.element('products')), /US product 1/);
  assert.equal(app.url, 'https://shop.example/products/?region=US&page=1');
});

test('cursor traversal shares one eight-second deadline and ignores a late response', async () => {
  const pending = deferred();
  const app = browser('https://shop.example/products/?region=EU&page=3', query =>
    query.has('after') ? pending.promise : page('EU', 1));
  await app.settled();
  assert.equal(app.timers.size, 1);
  assert.equal([...app.timers.values()][0].ms, 8000);
  assert.equal(app.calls.length, 2);
  assert.equal(app.calls[0].signal, app.calls[1].signal);
  app.expire();
  pending.resolve(page('EU', 2)); await app.settled();
  assert.equal(app.calls[0].signal.aborted, true);
  assert.equal(app.calls.length, 2);
  assert.equal(app.element('state-title').textContent, "Couldn't load products");
  assert.match(app.element('state-description').textContent, /took too long/);
  assert.equal(app.element('products').children.length, 0);
  assert.equal(app.element('next').disabled, true);
  assert.equal(app.timers.size, 0);
});

test('a failed traversal shows no partial page and Refresh retries the requested page', async () => {
  let failing = true;
  const app = browser('https://shop.example/products/?region=EU&page=3', query => {
    if (failing && query.has('after')) throw new Error('unavailable');
    return page('EU', query.has('after') ? Number(query.get('after')) / 25 + 1 : 1);
  });
  await app.settled();
  assert.equal(app.element('state-title').textContent, "Couldn't load products");
  assert.equal(app.element('products').children.length, 0);
  failing = false;
  app.click('refresh'); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 3');
  assert.match(text(app.element('products')), /EU product 3/);
});

test('product identities, exact decimal money, and names remain text', async () => {
  const app = browser('https://shop.example/products/?region=EU&page=3');
  await app.settled();
  const content = text(app.element('products'));
  assert.match(content, /9007199254740993/);
  assert.match(content, /9223372036854775807/);
  assert.match(content, /99,999,999,999,999,999\.99/);
  assert.match(content, /−100,000,000,000,000,000\.01/);
  assert.match(content, /<b>Seller<\/b>/);
});


test('Refresh clamps the current page when the refreshed catalog becomes shorter', async () => {
  let count = 3;
  const app = browser('https://shop.example/products/?region=EU&page=3', query =>
    page('EU', query.has('after') ? Number(query.get('after')) / 25 + 1 : 1, count));
  await app.settled();
  count = 1;
  app.click('refresh'); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 1');
  assert.match(text(app.element('products')), /EU product 1/);
  assert.equal(app.url, 'https://shop.example/products/?region=EU&page=1');
  assert.equal(app.element('next').disabled, true);
  assert.deepEqual(paths(app), [null, '25', '50', null]);
});

// End navigation must resolve a concrete shareable page without publishing a sentinel URL.
test('First returns to regional page 1 and Back restores the original page and URL fields', async () => {
  const app = browser('https://shop.example/products/?view=compact&region=EU&page=3#catalog');
  await app.settled();
  assert.equal(app.element('first').disabled, false);
  assert.equal(app.element('last').disabled, true);
  app.click('first'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?view=compact&region=EU&page=1#catalog');
  assert.match(text(app.element('products')), /EU product 1/);
  assert.equal(app.element('first').disabled, true);
  assert.equal(app.element('previous').disabled, true);
  assert.equal(app.element('last').disabled, false);
  assert.equal(app.entries.length, 2);
  app.back(); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 3');
  assert.match(text(app.element('products')), /EU product 3/);
  assert.equal(app.url, 'https://shop.example/products/?view=compact&region=EU&page=3#catalog');
});

test('Last resolves one shareable history entry and keeps the concrete URL while loading', async () => {
  const pending = deferred();
  let waiting = true;
  const app = browser('https://shop.example/products/?view=compact&region=EU&page=1#catalog', query => {
    const number = query.has('after') ? Number(query.get('after')) / 25 + 1 : 1;
    return number === 2 && waiting ? pending.promise : page('EU', number);
  });
  await app.settled();
  app.click('last'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?view=compact&region=EU&page=1#catalog');
  assert.equal(app.entries.length, 1);
  for (const id of ['first', 'previous', 'next', 'last']) assert.equal(app.element(id).disabled, true, id);
  waiting = false;
  pending.resolve(page('EU', 2)); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?view=compact&region=EU&page=3#catalog');
  assert.match(text(app.element('products')), /EU product 3/);
  assert.equal(app.element('last').disabled, true);
  assert.equal(app.element('next').disabled, true);
  assert.equal(app.entries.length, 2);
  const copied = browser(app.url); await copied.settled();
  assert.match(text(copied.element('products')), /EU product 3/);
  app.back(); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 1');
  app.forward(); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 3');
  assert.equal(app.entries.length, 2);
});

test('Last discovers an appended final page after returning to an earlier page', async () => {
  let count = 3;
  const app = browser(undefined, query => page('US', query.has('after') ? Number(query.get('after')) / 25 + 1 : 1, count));
  await app.settled();
  app.click('last'); await app.settled();
  assert.equal(app.element('page-label').textContent, 'Page 3');
  app.click('first'); await app.settled();
  count = 4;
  app.click('last'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=US&page=4');
  assert.match(text(app.element('products')), /US product 4/);
  assert.equal(app.element('last').disabled, true);
});

test('Last retains the final populated page when its continuation becomes empty', async () => {
  const app = browser(undefined, query => {
    const number = query.has('after') ? Number(query.get('after')) / 25 + 1 : 1;
    return number === 3 ? { region: 'US', items: [], nextAfter: null } : page('US', number);
  });
  await app.settled();
  app.click('last'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=US&page=2');
  assert.match(text(app.element('products')), /US product 2/);
  assert.equal(app.element('first').disabled, false);
  assert.equal(app.element('last').disabled, true);
  assert.equal(app.element('next').disabled, true);
  assert.equal(app.entries.length, 2);
});

test('First and Last are disabled for empty and single-page catalogs', async t => {
  for (const empty of [false, true]) {
    await t.test(empty ? 'empty catalog' : 'single-page catalog', async () => {
      const app = browser(undefined, () => empty ? { region: 'US', items: [], nextAfter: null } : page('US', 1, 1));
      await app.settled();
      for (const id of ['first', 'previous', 'next', 'last']) assert.equal(app.element(id).disabled, true, id);
      app.click('first'); app.click('last'); await app.settled();
      assert.equal(app.calls.length, 1);
      assert.equal(app.entries.length, 1);
    });
  }
});

test('a superseded Last traversal cannot overwrite a newer region or its navigation controls', async () => {
  const pending = deferred();
  const app = browser('https://shop.example/products/?region=EU&page=1', query =>
    query.get('region') === 'EU' && query.has('after') ? pending.promise : page(query.get('region'), 1));
  await app.settled();
  app.click('last'); await app.settled();
  const oldSignal = app.calls.at(-1).signal;
  app.region('ASIA'); await app.settled();
  pending.resolve(page('EU', 2)); await app.settled();
  assert.equal(oldSignal.aborted, true);
  assert.equal(app.url, 'https://shop.example/products/?region=ASIA&page=1');
  assert.match(text(app.element('products')), /ASIA product 1/);
  assert.equal(app.element('first').disabled, true);
  assert.equal(app.element('last').disabled, false);
  assert.equal(app.calls.at(-1).query.get('region'), 'ASIA');
});

test('Last shares one eight-second deadline and Refresh retries the end after timeout', async () => {
  const pending = deferred();
  let stalled = true;
  const app = browser('https://shop.example/products/?region=EU&page=2', query => {
    const number = query.has('after') ? Number(query.get('after')) / 25 + 1 : 1;
    return stalled && number === 4 ? pending.promise : page('EU', number, 4);
  });
  await app.settled();
  const beforeLast = app.calls.length;
  app.click('last'); await app.settled();
  assert.equal(app.timers.size, 1);
  assert.equal([...app.timers.values()][0].ms, 8000);
  const traversal = app.calls.slice(beforeLast);
  assert.ok(traversal.length >= 2, 'Last traverses more than one continuation');
  assert.ok(traversal.every(call => call.signal === traversal[0].signal));
  assert.equal(app.url, 'https://shop.example/products/?region=EU&page=2');
  app.expire();
  pending.resolve(page('EU', 4, 4)); await app.settled();
  assert.equal(traversal[0].signal.aborted, true);
  assert.equal(app.element('state-title').textContent, "Couldn't load products");
  assert.match(app.element('state-description').textContent, /took too long/);
  assert.equal(app.element('products').children.length, 0);
  assert.equal(app.url, 'https://shop.example/products/?region=EU&page=2');
  assert.equal(app.entries.length, 1);
  for (const id of ['first', 'previous', 'next', 'last']) assert.equal(app.element(id).disabled, true, id);
  assert.equal(app.timers.size, 0);
  stalled = false;
  app.click('refresh'); await app.settled();
  assert.equal(app.url, 'https://shop.example/products/?region=EU&page=4');
  assert.match(text(app.element('products')), /EU product 4/);
  assert.equal(app.element('first').disabled, false);
  assert.equal(app.element('last').disabled, true);
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
