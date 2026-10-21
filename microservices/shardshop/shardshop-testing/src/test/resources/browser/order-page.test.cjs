// Execute the shipped script against controlled DOM, HTTP, history and clock boundaries.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2], 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const stamp = '2026-10-21T12:34:56.123456Z';
const order = id => ({ orderId: id, buyerId: '9223372036854775806', buyerName: '<Ada> Lovelace', createdAt: stamp,
  status: 'CANCELLED', cancellationReason: '<script>literal & safe</script>',
  total: '99999999999999999.99', currency: 'USD', items: [{ productId: '9007199254740993', productName: '<Tea & coffee>', quantity: 2 }] });
const page = (region = 'US', id = '9223372036854775807', more = false) => ({
  region, items: [order(id)], nextAfter: more ? { createdAt: stamp, orderId: id } : null });

class Element {
  constructor(tag = 'div') { this.tag = tag; this.children = []; this.listeners = {}; this.attributes = {}; this.value = ''; this.disabled = false; this.style = {}; this.parentNode = null; }
  set textContent(text) { this.text = String(text); this.children = []; }
  get textContent() { return (this.text || '') + this.children.map(child => child.textContent).join(''); }
  set innerHTML(_) { throw new Error('HTML injection is forbidden'); }
  append(...nodes) { for (const node of nodes) { const children = node.tag === '#fragment' ? node.children : [node]; for (const child of children) { child.parentNode = this; this.children.push(child); } } }
  replaceChildren(...nodes) { this.text = ''; this.children = []; this.append(...nodes); }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener(event, listener) { this.listeners[event] = listener; }
  contains(other) { return this === other || this.children.some(child => child.contains(other)); }
  getBoundingClientRect() { return this.tag === 'button' ? { left: 1000, top: 700, bottom: 725, width: 120, height: 25 } : { width: 310, height: 230 }; }
  focus() { this.owner.activeElement = this; this.fire('focus'); }
  select() { this.owner.activeElement = this; }
  remove() { this.parentNode.children = this.parentNode.children.filter(child => child !== this); }
  fire(event = 'click', extra = {}) { if (!this.disabled) this.listeners[event]?.({ target: this, preventDefault() {}, ...extra }); }
  get visibleText() { return this.hidden ? '' : (this.text || '') + this.children.map(child => child.visibleText).join(''); }
}

async function browser({ url = 'http://localhost/orders/', respond = () => page(), clipboard = true, copyDenied = false, fallbackCopy = true } = {}) {
  const elements = new Map();
  let document;
  const create = tag => { const result = new Element(tag); result.owner = document; return result; };
  const el = id => { if (!elements.has(id)) elements.set(id, create('div')); return elements.get(id); };
  const calls = [], timers = new Map(), events = {};
  let now = 0, timerId = 0, position = 0;
  const locations = [url];
  const copied = [], documentEvents = {};
  document = { getElementById: el, createElement: create, createDocumentFragment: () => create('#fragment'),
    addEventListener: (event, listener) => documentEvents[event] = listener,
    execCommand: command => { assert.equal(command, 'copy'); if (fallbackCopy) copied.push(document.activeElement.value); return fallbackCopy; } };
  document.body = create('body');
  const window = { innerWidth: 1200, innerHeight: 800, location: { href: url }, addEventListener: (event, listener) => events[event] = listener };
  window.history = {
    pushState: (_, __, href) => { locations.splice(++position); locations.push(href); window.location.href = href; },
    replaceState: (_, __, href) => { locations[position] = href; window.location.href = href; },
    back: () => { if (position) { window.location.href = locations[--position]; events.popstate(); } },
    forward: () => { if (position < locations.length - 1) { window.location.href = locations[++position]; events.popstate(); } }
  };
  class Clock extends Date { static now() { return now; } }
  vm.runInNewContext(source, {
    document, navigator: { clipboard: clipboard ? { writeText: async value => { if (copyDenied) throw new Error('denied'); copied.push(value); } } : undefined },
    window, URL, URLSearchParams, AbortController, Date: Clock,
    setTimeout: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
    clearTimeout: id => timers.delete(id),
    fetch: async (path, options) => {
      const query = new URL(path, window.location.href).searchParams;
      calls.push({ path, query, options });
      const body = await respond(query, options, calls.length, value => now += value);
      return { ok: body.status === undefined || body.status === 200, status: body.status || 200, json: async () => body };
    }
  });
  await flush();
  return { el, calls, window, timers, copied, document, documentEvents, tick: value => now += value, query: () => new URL(window.location.href).searchParams };
}

test('renders exact order IDs, names, decimal money, UTC microseconds, quantities and literal cancellation text', async () => {
  const b = await browser();
  assert.equal(b.calls[0].query.get('sort'), 'DESC');
  assert.equal(b.calls[0].query.get('limit'), '25');
  assert.equal(b.calls[0].options.cache, 'no-store');
  const text = b.el('orders').textContent;
  for (const value of ['9223372036854775807', '9223372036854775806', '<Ada> Lovelace', '<Tea & coffee>', '× 2',
    '99,999,999,999,999,999.99', 'USD', '2026-10-21 12:34:56.123456 UTC', 'CANCELLED', '<script>literal & safe</script>']) {
    assert.ok(text.includes(value), value);
  }
  assert.equal(b.el('state').hidden, true);
  assert.equal(b.el('results').attributes['aria-busy'], 'false');
  assert.equal(b.el('first').disabled, true);
  assert.equal(b.el('last').disabled, true);
});

function regionalPages(query) {
  const cursor = query.get('afterOrderId');
  return page(query.get('region'), cursor === null ? '9007199254740999' : cursor === '9007199254740999' ? '9007199254740998' : '9007199254740997', cursor !== '9007199254740998');
}

test('Next, Previous, Last and First use paired exact cursors; refresh resolves the same page again', async () => {
  const b = await browser({ respond: regionalPages });
  b.el('next').fire(); await flush();
  assert.equal(b.el('page-label').textContent, 'Page 2');
  assert.equal(b.calls.at(-1).query.get('afterCreatedAt'), stamp);
  assert.equal(b.calls.at(-1).query.get('afterOrderId'), '9007199254740999');
  b.el('last').fire(); await flush();
  assert.equal(b.el('page-label').textContent, 'Page 3');
  assert.equal(b.el('last').disabled, true);
  assert.equal(b.query().get('page'), '3');
  const before = b.el('orders').textContent;
  const count = b.calls.length;
  b.el('refresh').fire(); await flush();
  assert.equal(b.calls.length - count, 3);
  assert.equal(b.el('orders').textContent, before);
  b.el('previous').fire(); await flush();
  assert.equal(b.query().get('page'), '2');
  b.el('first').fire(); await flush();
  assert.equal(b.query().get('page'), '1');
  assert.equal(b.el('previous').disabled, true);
});

test('copied links resolve pages with region, product filter and ASC sorting; filter changes reset page one', async () => {
  const b = await browser({ url: 'http://localhost/orders/?region=EU&sort=ASC&productId=9007199254740993&page=3', respond: regionalPages });
  assert.equal(b.calls.length, 3);
  assert.equal(b.query().get('page'), '3');
  assert.equal(b.el('product-id').value, '9007199254740993');
  for (const call of b.calls) {
    assert.equal(call.query.get('region'), 'EU'); assert.equal(call.query.get('sort'), 'ASC');
    assert.equal(call.query.get('productId'), '9007199254740993');
  }
  b.el('sort').fire(); await flush();
  assert.equal(b.query().get('page'), '1');
  assert.equal(b.calls.at(-1).query.get('afterOrderId'), null);
  b.el('product-id').value = '9223372036854775807'; b.el('product-filter').fire('submit'); await flush();
  assert.equal(b.query().get('productId'), '9223372036854775807');
  b.el('clear-filter').fire(); await flush();
  assert.equal(b.query().get('productId'), null); assert.equal(b.calls.at(-1).query.get('productId'), null);
  b.el('next').fire(); await flush();
  b.el('region').value = 'ASIA'; b.el('region').fire('change'); await flush();
  assert.equal(b.query().get('page'), '1'); assert.equal(b.calls.at(-1).query.get('region'), 'ASIA');
});

test('browser back and forward restore every selected control and the numbered page', async () => {
  const b = await browser({ respond: regionalPages });
  b.el('product-id').value = '9007199254740993'; b.el('product-filter').fire('submit'); await flush();
  b.el('sort').fire(); await flush();
  b.el('next').fire(); await flush();
  b.window.history.back(); await flush();
  assert.equal(b.el('page-label').textContent, 'Page 1'); assert.equal(b.el('created-heading').attributes['aria-sort'], 'ascending');
  b.window.history.back(); await flush();
  assert.equal(b.el('created-heading').attributes['aria-sort'], 'descending'); assert.equal(b.el('product-id').value, '9007199254740993');
  b.window.history.forward(); await flush();
  assert.equal(b.el('created-heading').attributes['aria-sort'], 'ascending'); assert.equal(b.calls.at(-1).query.get('sort'), 'ASC');
});

test('invalid canonical product filters issue no read and clearing restores the list', async () => {
  for (const id of ['0', '01', '-1', '+1', '1e3', '1.0', ' 1', '1\n', '1\r\n', '1\t', '9223372036854775808']) {
    const b = await browser({ url: 'http://localhost/orders/?productId=' + encodeURIComponent(id) });
    assert.equal(b.calls.length, 0, id);
    assert.equal(b.el('product-id').attributes['aria-invalid'], 'true');
    assert.ok(b.el('state-description').textContent.includes('canonical positive product ID'));
    b.el('clear-filter').fire(); await flush();
    assert.equal(b.calls.length, 1); assert.equal(b.el('state').hidden, true);
  }
});

test('empty, malformed and failed responses show a complete state with no stale rows; refresh can recover', async () => {
  for (const body of [{ region: 'US', items: [], nextAfter: null }, { status: 503 },
    { ...page(), items: [{ ...order('123'), total: 19.95 }] }]) {
    let current = body;
    const b = await browser({ respond: () => current });
    assert.equal(b.el('orders').children.length, 0);
    assert.equal(b.el('state').hidden, false);
    for (const id of ['first', 'previous', 'next', 'last']) assert.equal(b.el(id).disabled, true);
    assert.ok(b.el('state-title').textContent.includes(body.items?.length === 0 ? 'No orders' : "Couldn't load"));
    current = page(); b.el('refresh').fire(); await flush();
    assert.equal(b.el('state').hidden, true);
  }
});

test('legacy orders without recorded lines still render rather than failing the entire page', async () => {
  const body = page(); body.items[0].items = [];
  const b = await browser({ respond: () => body });
  assert.equal(b.el('state').hidden, true);
  assert.ok(b.el('orders').textContent.includes('No recorded items'));
  assert.ok(b.el('orders').textContent.includes('9223372036854775807'));
});

test('loading disables navigation and superseded HTTP completion cannot overwrite a changed region', async () => {
  let release;
  const b = await browser({ respond: query => query.get('region') === 'US' ? new Promise(resolve => release = resolve) : page('EU') });
  assert.equal(b.el('results').attributes['aria-busy'], 'true');
  assert.equal(b.el('state-title').textContent, 'Loading orders');
  for (const id of ['first', 'previous', 'next', 'last']) assert.equal(b.el(id).disabled, true);
  b.el('region').value = 'EU'; b.el('region').fire('change'); await flush();
  assert.equal(b.calls[0].options.signal.aborted, true);
  release(page('US')); await flush();
  assert.equal(b.el('region-badge').textContent, 'EU'); assert.equal(b.query().get('region'), 'EU');
});

test('one eight-second budget bounds copied-page traversal and Last; timeout leaves refresh available', async () => {
  for (const last of [false, true]) {
    let advancing = !last;
    const b = await browser({
      url: 'http://localhost/orders/?region=US&sort=DESC&page=' + (last ? '1' : '3'),
      respond: (query, _, __, advance) => { if (advancing) advance(4500); return regionalPages(query); }
    });
    if (last) { advancing = true; b.el('last').fire(); await flush(); }
    assert.equal(b.calls.length, last ? 3 : 2, 'Traversal must stop at its shared budget');
    assert.equal(b.el('state-title').textContent, "Couldn't load orders");
    assert.ok(b.el('state-description').textContent.includes('too long'));
    assert.equal(b.el('orders').children.length, 0);
    assert.equal(b.el('refresh').disabled, false);
    assert.equal(b.timers.size, 0);
  }
});

test('the deadline aborts a stalled HTTP request and does not wait for another page timeout', async () => {
  const b = await browser({ respond: (_, options) => new Promise((resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new Error('aborted')));
  }) });
  assert.equal(b.timers.size, 1);
  const timer = [...b.timers.values()][0]; assert.equal(timer.delay, 8000);
  timer.callback(); await flush();
  assert.equal(b.calls[0].options.signal.aborted, true);
  assert.ok(b.el('state-description').textContent.includes('too long'));
  assert.equal(b.el('results').attributes['aria-busy'], 'false');
});

function descendants(root, predicate) {
  return root.children.flatMap(child => [...(predicate(child) ? [child] : []), ...descendants(child, predicate)]);
}
function entities(b) { return descendants(b.el('orders'), node => node.className === 'entity'); }

test('Created column button toggles sort, announces direction and resets cursor and page', async () => {
  const b = await browser({ respond: regionalPages });
  assert.equal(b.el('created-heading').attributes['aria-sort'], 'descending');
  b.el('next').fire(); await flush();
  b.el('sort').fire(); await flush();
  assert.equal(b.calls.at(-1).query.get('sort'), 'ASC');
  assert.equal(b.calls.at(-1).query.get('afterOrderId'), null);
  assert.equal(b.query().get('page'), '1');
  assert.equal(b.el('created-heading').attributes['aria-sort'], 'ascending');
  assert.ok(b.el('sort').attributes['aria-label'].includes('Sort newest first'));
  b.el('sort').fire(); await flush();
  assert.equal(b.calls.at(-1).query.get('sort'), 'DESC');
  assert.equal(b.el('sort-direction').textContent, '↓');
});

test('one product input trims names, preserves literal Unicode text and switches URL filter keys', async () => {
  const b = await browser({ respond: regionalPages });
  b.el('next').fire(); await flush();
  b.el('product-id').value = '  Tea_% 🍵  '; b.el('product-filter').fire('submit'); await flush();
  assert.equal(b.calls.at(-1).query.get('productName'), 'Tea_% 🍵');
  assert.equal(b.calls.at(-1).query.get('productId'), null);
  assert.equal(b.calls.at(-1).query.get('afterOrderId'), null);
  assert.equal(b.query().get('page'), '1');
  assert.equal(b.query().get('productName'), 'Tea_% 🍵');
  b.el('next').fire(); await flush();
  assert.equal(b.calls.at(-1).query.get('productName'), 'Tea_% 🍵');
  b.el('product-id').value = '9007199254740993'; b.el('product-filter').fire('submit'); await flush();
  assert.equal(b.query().get('productName'), null);
  assert.equal(b.query().get('productId'), '9007199254740993');
  b.window.history.back(); await flush();
  assert.equal(b.el('product-id').value, 'Tea_% 🍵');
  assert.equal(b.query().get('page'), '2');
  b.el('clear-filter').fire(); await flush();
  assert.equal(b.query().get('productName'), null);
});

test('copied productName links restore names and malformed names fail before HTTP', async () => {
  const b = await browser({ url: 'http://localhost/orders/?productName=Tea%20%26%20coffee&sort=ASC&page=2', respond: regionalPages });
  assert.equal(b.calls.length, 2);
  assert.equal(b.el('product-id').value, 'Tea & coffee');
  assert.equal(b.calls.at(-1).query.get('productName'), 'Tea & coffee');
  b.el('product-id').value = '🍵'.repeat(200); b.el('product-filter').fire('submit'); await flush();
  assert.equal(b.calls.at(-1).query.get('productName'), '🍵'.repeat(200), 'Name limit counts Unicode code points');
  for (const name of ['x'.repeat(201), '🍵'.repeat(201), 'tea\u0000', 'tea\ud800']) {
    const invalid = await browser();
    const count = invalid.calls.length;
    invalid.el('product-id').value = name; invalid.el('product-filter').fire('submit'); await flush();
    assert.equal(invalid.calls.length, count);
    assert.equal(invalid.el('product-id').attributes['aria-invalid'], 'true');
  }
  for (const value of ['0', '01', '9223372036854775808']) {
    const invalid = await browser(); const count = invalid.calls.length;
    invalid.el('product-id').value = value; invalid.el('product-filter').fire('submit'); await flush();
    assert.equal(invalid.calls.length, count);
  }
});

test('buyer and product names are default safe text; hover popovers expose selectable exact details and copy actions', async () => {
  const b = await browser();
  assert.ok(b.el('orders').visibleText.includes('<Ada> Lovelace'));
  assert.ok(b.el('orders').visibleText.includes('<Tea & coffee>'));
  assert.ok(!b.el('orders').visibleText.includes('9223372036854775806'));
  assert.ok(!b.el('orders').visibleText.includes('9007199254740993'));
  assert.equal(entities(b).length, 2);
  for (const [index, expected] of [[0, ['<Ada> Lovelace', '9223372036854775806']], [1, ['<Tea & coffee>', '9007199254740993']]]) {
    const wrapper = entities(b)[index], [trigger, panel] = wrapper.children;
    trigger.fire('mouseenter');
    assert.equal(trigger.attributes['aria-expanded'], 'true');
    assert.equal(panel.hidden, false);
    assert.equal(panel.attributes.role, 'dialog');
    assert.ok(panel.textContent.includes(expected[0])); assert.ok(panel.textContent.includes(expected[1]));
    assert.ok(parseFloat(panel.style.left) + 310 <= b.window.innerWidth - 12);
    assert.ok(parseFloat(panel.style.top) + 230 <= b.window.innerHeight - 12);
    wrapper.fire('mouseleave');
    const timer = [...b.timers.values()].find(timer => timer.delay === 180);
    assert.ok(timer, 'Mouse can cross the gap before the panel closes');
    panel.fire('mouseenter');
    assert.equal([...b.timers.values()].some(timer => timer.delay === 180), false);
    const buttons = descendants(panel, node => node.tag === 'button');
    for (const [i, button] of buttons.entries()) { button.fire(); await flush(); assert.equal(b.copied.at(-1), expected[i]); }
    assert.ok(panel.textContent.includes('Copied to clipboard.'));
  }
});

test('details support focus, Tab into copy buttons, pinned click, Escape and outside dismissal', async () => {
  const b = await browser(); const wrapper = entities(b)[0], [trigger, panel] = wrapper.children;
  const copy = descendants(panel, node => node.tag === 'button')[0];
  trigger.focus(); assert.equal(panel.hidden, false);
  trigger.fire('click');
  b.document.activeElement = copy; wrapper.fire('focusout', { relatedTarget: copy });
  assert.equal(panel.hidden, false);
  b.documentEvents.keydown({ key: 'Escape', preventDefault() {} });
  assert.equal(panel.hidden, true); assert.equal(b.document.activeElement, trigger);
  trigger.fire('click'); assert.equal(panel.hidden, false);
  b.documentEvents.click({ target: panel }); assert.equal(panel.hidden, false);
  b.documentEvents.click({ target: b.document.body }); assert.equal(panel.hidden, true);
  trigger.fire('click'); assert.equal(panel.hidden, false);
  trigger.fire('click'); assert.equal(panel.hidden, true);
  trigger.fire('mouseenter'); b.document.activeElement = b.document.body;
  wrapper.fire('mouseleave');
  [...b.timers.values()].find(timer => timer.delay === 180).callback();
  assert.equal(panel.hidden, true, 'Unfocused hover details close after pointer leaves');
});

test('clipboard rejection and failed fallback give manual-copy guidance without claiming success', async () => {
  for (const options of [{ copyDenied: true }, { clipboard: false, fallbackCopy: false }, { clipboard: false }]) {
    const b = await browser(options); const [trigger, panel] = entities(b)[0].children;
    trigger.fire('click'); descendants(panel, node => node.tag === 'button')[0].fire(); await flush();
    if (options.clipboard === false && options.fallbackCopy !== false) {
      assert.equal(b.copied.at(-1), '<Ada> Lovelace'); assert.ok(panel.textContent.includes('Copied to clipboard.'));
    } else {
      assert.ok(panel.textContent.includes('copy it manually')); assert.ok(!panel.textContent.includes('Copied to clipboard.'));
    }
  }
});

test('required buyer and item names reject malformed responses', async () => {
  for (const mutate of [item => delete item.buyerName, item => item.buyerName = ' ', item => delete item.items[0].productName]) {
    const data = page(); mutate(data.items[0]);
    const b = await browser({ respond: () => data });
    assert.equal(b.el('orders').children.length, 0);
    assert.ok(b.el('state-description').textContent.includes('unexpected response'));
  }
});

test('filter normalization preserves literal FEFF and rejects boundary controls for input and copied links', async () => {
  const name = '\uFEFFTea\uFEFF';
  const copied = await browser({ url: 'http://localhost/orders/?productName=' + encodeURIComponent(name) });
  assert.equal(copied.el('product-id').value, name);
  assert.equal(copied.calls.at(-1).query.get('productName'), name);
  const entered = await browser();
  entered.el('product-id').value = '\u2003' + name + '\u00A0';
  entered.el('product-filter').fire('submit'); await flush();
  assert.equal(entered.calls.at(-1).query.get('productName'), name);
  assert.equal(entered.query().get('productName'), name);
  entered.el('product-id').value = ' \u2003\u00A0 ';
  entered.el('product-filter').fire('submit'); await flush();
  assert.equal(entered.calls.at(-1).query.get('productName'), null);
  assert.equal(entered.query().get('productName'), null);
  for (const value of ['\nTea', 'Tea\n', '\n', '\tTea\t']) {
    const invalidLink = await browser({ url: 'http://localhost/orders/?productName=' + encodeURIComponent(value) });
    assert.equal(invalidLink.calls.length, 0, 'Copied link: ' + JSON.stringify(value));
    assert.equal(invalidLink.el('product-id').attributes['aria-invalid'], 'true');
    const invalidInput = await browser(); const before = invalidInput.calls.length;
    invalidInput.el('product-id').value = value; invalidInput.el('product-filter').fire('submit'); await flush();
    assert.equal(invalidInput.calls.length, before, 'Entered input: ' + JSON.stringify(value));
    assert.equal(invalidInput.el('product-id').attributes['aria-invalid'], 'true');
  }
});

test('details fit the content viewport excluding scrollbars and constrain width before measurement', async () => {
  const b = await browser();
  b.window.innerWidth = 1280; b.window.innerHeight = 800;
  b.document.documentElement = { clientWidth: 1265, clientHeight: 785 };
  const [trigger, panel] = entities(b)[0].children;
  trigger.getBoundingClientRect = () => ({ left: 1220, top: 720, bottom: 745 });
  trigger.fire('mouseenter');
  assert.ok(parseFloat(panel.style.left) + 310 <= 1265 - 12, 'Right edge leaves a margin inside the scrollbar');
  assert.ok(parseFloat(panel.style.top) + 230 <= 785 - 12);
  b.document.documentElement.clientWidth = 280;
  panel.getBoundingClientRect = () => ({ width: Math.min(310, parseFloat(panel.style.maxWidth) || 310), height: 230 });
  b.window.innerWidth = 295;
  trigger.fire('mouseenter');
  assert.equal(panel.style.maxWidth, '256px');
  assert.ok(parseFloat(panel.style.left) + 256 <= 280 - 12, 'Narrow viewport constrains popover before positioning');
});
