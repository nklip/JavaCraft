// Execute the shipped control script against the shipped page markup with controlled DOM, HTTP and timers.
// Arguments: control.js, then the index.html of the order producer, the product reader and the product seeder.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const [source, producerPage, readerPage, seederPage] = process.argv.slice(2).map(file => fs.readFileSync(file, 'utf8'));
const flush = async () => { for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve)); };

class Element {
  constructor(tag, attributes = {}) {
    this.tag = tag; this.children = []; this.listeners = {}; this.attributes = { ...attributes };
    this.value = ''; this.disabled = false; this.hidden = 'hidden' in attributes; this.className = attributes.class || '';
  }
  set textContent(text) { this.text = String(text); this.children = []; }
  get textContent() { return (this.text || '') + this.children.map(child => child.textContent).join(''); }
  set innerHTML(_) { throw new Error('HTML injection is forbidden'); }
  append(...nodes) { for (const node of nodes) this.children.push(...(node.tag === '#fragment' ? node.children : [node])); }
  replaceChildren(...nodes) { this.text = ''; this.children = []; this.append(...nodes); }
  setAttribute(key, value) { this.attributes[key] = String(value); }
  getAttribute(key) { return key in this.attributes ? this.attributes[key] : null; }
  addEventListener(event, listener) { this.listeners[event] = listener; }
  focus() { this.focused = true; }
  fire(event = 'click') { if (!this.disabled) this.listeners[event]?.({ target: this, preventDefault() {} }); }
}

const status = (overrides = {}) => ({
  workload: 'order-producer', unit: 'new order starts/s', state: 'RUNNING', targetRate: 0.5, paused: false,
  effectiveRate: 0.5, defaultRate: 0.5, maximumRate: 100, rateStep: 0.1, achievedRate: 0.48, starts: 12,
  inFlight: 1, requests: 3400, errors: 2,
  traffic: [{ category: 'startup', requests: 3352, retries: 1, errors: 1 }, { category: 'allocation', requests: 13, retries: 0, errors: 0 },
    { category: 'submission', requests: 13, retries: 1, errors: 1 }, { category: 'polling', requests: 22, retries: 0, errors: 0 }],
  limit: null, run: null, revision: 3, ...overrides });

// Builds the elements with IDs from the real markup; elements that a page lacks stay absent.
async function browser(html, { respond = () => status() } = {}) {
  const elements = new Map();
  for (const match of html.matchAll(/<(\w+)((?:\s+[\w-]+(?:="[^"]*")?)*)\s*\/?>/g)) {
    const attributes = Object.fromEntries([...match[2].matchAll(/([\w-]+)(?:="([^"]*)")?/g)].map(([, key, value]) => [key, value ?? '']));
    if (attributes.id) elements.set(attributes.id, new Element(match[1], attributes));
  }
  const calls = [], timers = new Map();
  let timerId = 0;
  const document = { getElementById: id => elements.get(id) ?? null, createElement: tag => new Element(tag),
    createDocumentFragment: () => new Element('#fragment') };
  vm.runInNewContext(source, {
    document, Intl, AbortController, Date, Number, JSON, Object, Math, String, Array, Error, Promise,
    setTimeout: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
    clearTimeout: id => timers.delete(id),
    fetch: async (path, options) => {
      const call = { path, method: options.method, options, body: options.body === undefined ? undefined : JSON.parse(options.body) };
      calls.push(call);
      const answer = await respond(call, calls.length);
      if (answer instanceof Error) throw answer;
      const { code = 200, body = answer } = answer && answer.code ? answer : {};
      return { ok: code >= 200 && code < 300, status: code, json: async () => body };
    }
  });
  await flush();
  const poll = async () => {
    const pending = [...timers.entries()].filter(([, timer]) => timer.delay === 2000);
    assert.equal(pending.length, 1, 'One scheduled status poll');
    timers.delete(pending[0][0]);
    pending[0][1].callback();
    await flush();
  };
  return { el: id => elements.get(id), has: id => elements.has(id), calls, timers, poll };
}

test('renders the status, units and traffic purposes, and polls every two seconds with no-store', async () => {
  const b = await browser(producerPage);
  assert.equal(b.calls[0].path, '/api/v1/order-producer/control');
  assert.equal(b.calls[0].method, 'GET');
  assert.equal(b.calls[0].options.cache, 'no-store');
  assert.equal(b.el('state-badge').textContent, 'Running');
  assert.equal(b.el('state-badge').attributes['data-state'], 'RUNNING');
  assert.equal(b.el('rate').value, '0.5');
  assert.ok(b.el('effective').textContent.startsWith('0.5'));
  assert.ok(b.el('achieved').textContent.includes('new order starts/s, last 10 s'));
  assert.ok(b.el('requests').textContent.startsWith('3,400'));
  assert.equal(b.el('errors').className, 'has-errors');
  assert.ok(b.el('rate-help').textContent.includes('0 to 100 new order starts/s'));
  assert.ok(b.el('rate-help').textContent.includes('Default 0.5, restored when the process restarts'));
  const rows = b.el('traffic').textContent;
  for (const text of ['Startup', 'Catalog lookups and buyer creation', 'Order allocation', 'Order submission', 'Status polling', '3,352'])
    assert.ok(rows.includes(text), text);
  assert.equal(b.el('pause').disabled, false);
  assert.equal(b.el('resume').disabled, true);
  assert.equal(b.el('state').hidden, true);
  assert.equal(b.el('limit').hidden, true);
  assert.equal(b.has('run-start'), false, 'Only the seeder page has runs');
  await b.poll();
  assert.equal(b.calls.length, 2);
});

test('Apply sends one exact JSON number and shows the accepted setting', async () => {
  const b = await browser(producerPage, { respond: call => call.method === 'PUT'
    ? status({ targetRate: call.body.rate, effectiveRate: call.body.rate, revision: 4 }) : status() });
  b.el('rate').value = ' 12.345 ';
  b.el('rate').fire('input');
  b.el('rate-form').fire('submit');
  await flush();
  const put = b.calls.at(-1);
  assert.equal(put.path, '/api/v1/order-producer/control/rate');
  assert.equal(put.method, 'PUT');
  assert.equal(put.options.headers['Content-Type'], 'application/json');
  assert.deepEqual(put.body, { rate: 12.345 });
  assert.equal(b.el('rate').value, '12.345');
  assert.equal(b.el('revision').textContent, 'Setting revision 4');
  assert.ok(b.el('message').textContent.includes('Target rate set to 12.345 new order starts/s.'));
  assert.equal(b.el('message').hidden, false);
});

test('invalid input is rejected in the page without a request', async () => {
  const b = await browser(producerPage);
  for (const value of ['', '-1', '+1', '1e3', '12.3456', 'abc', '100.5', '01', '1.']) {
    b.el('rate').value = value;
    b.el('rate-form').fire('submit');
    await flush();
    assert.equal(b.calls.length, 1, value);
    assert.equal(b.el('rate').attributes['aria-invalid'], 'true', value);
    assert.ok(b.el('rate-error').textContent.includes('from 0 to 100 with at most three decimal places'), value);
  }
});

test('a rejected update shows the server message and keeps the previous status', async () => {
  const b = await browser(producerPage, { respond: call => call.method === 'PUT'
    ? { code: 400, body: { code: 'INVALID_RATE', message: 'Rate must be from 0 to the maximum. The previous setting is unchanged.' } } : status() });
  b.el('rate').value = '5';
  b.el('rate-form').fire('submit');
  await flush();
  assert.equal(b.el('message').textContent, 'Not applied: Rate must be from 0 to the maximum. The previous setting is unchanged.');
  assert.equal(b.el('rate').attributes['aria-invalid'], 'true');
  assert.equal(b.el('revision').textContent, 'Setting revision 3');
  assert.equal(b.el('apply').disabled, false);
});

test('step buttons change only the input, clamp to the bounds, and polls keep an unapplied value', async () => {
  const b = await browser(producerPage);
  b.el('increase').fire(); b.el('increase').fire();
  assert.equal(b.el('rate').value, '0.7');
  for (let i = 0; i < 10; i++) b.el('decrease').fire();
  assert.equal(b.el('rate').value, '0');
  b.el('rate').value = '99.95';
  b.el('increase').fire();
  assert.equal(b.el('rate').value, '100');
  assert.equal(b.calls.length, 1, 'Steps send nothing before Apply');
  await b.poll();
  assert.equal(b.el('rate').value, '100', 'A poll does not overwrite the edited value');
});

test('Pause and Resume switch availability and report a drain of the work in flight', async () => {
  let paused = false;
  const b = await browser(producerPage, { respond: call => {
    if (call.path.endsWith('/pause')) paused = true;
    if (call.path.endsWith('/resume')) paused = false;
    return status({ paused, effectiveRate: paused ? 0 : 0.5, state: paused ? 'PAUSING' : 'RUNNING' });
  } });
  b.el('pause').fire();
  await flush();
  assert.equal(b.calls.at(-1).method, 'POST');
  assert.equal(b.calls.at(-1).path, '/api/v1/order-producer/control/pause');
  assert.equal(b.el('state-badge').textContent, 'Pausing');
  assert.ok(b.el('message').textContent.startsWith('Pausing: 1 operation(s) in flight finish first.'));
  assert.ok(b.el('effective').textContent.includes('paused · target 0.5'));
  assert.equal(b.el('pause').disabled, true);
  assert.equal(b.el('resume').disabled, false);
  assert.equal(b.el('announcement').textContent, 'Pausing: 1 operation(s) in flight finish first.');
  b.el('resume').fire();
  await flush();
  assert.equal(b.el('message').textContent, 'Resumed at 0.5 new order starts/s.');
  assert.equal(b.el('resume').disabled, true);
});

test('a status that arrives after a newer update does not replace it', async () => {
  let release;
  const b = await browser(producerPage, { respond: (call, count) => {
    if (call.method === 'GET' && count === 2) return new Promise(resolve => { release = () => resolve(status({ revision: 3 })); });
    return call.method === 'PUT' ? status({ targetRate: 7, effectiveRate: 7, revision: 5 }) : status();
  } });
  const poll = b.poll();
  b.el('rate').value = '7';
  b.el('rate-form').fire('submit');
  await flush();
  release();
  await poll; await flush();
  assert.equal(b.el('revision').textContent, 'Setting revision 5');
  assert.equal(b.el('rate').value, '7');
});

test('a failed first load shows the error state; later failures keep the last values as stale', async () => {
  let fail = true;
  const b = await browser(readerPage, { respond: () => fail ? new Error('offline') : status({ workload: 'product-reader',
    unit: 'product read starts/s', traffic: [{ category: 'reads', requests: 10, retries: 1, errors: 1 }] }) });
  assert.equal(b.el('state').hidden, false);
  assert.equal(b.el('state-title').textContent, "Couldn't load the workload status");
  assert.equal(b.el('apply').disabled, true);
  assert.equal(b.el('pause').disabled, true);
  fail = false;
  await b.poll();
  assert.equal(b.el('state').hidden, true);
  assert.ok(b.el('traffic').textContent.includes('Product reads'));
  fail = true;
  await b.poll();
  assert.ok(b.el('message').textContent.includes('last successful update'));
  assert.ok(b.el('traffic').textContent.includes('Product reads'));
  fail = false;
  await b.poll();
  assert.equal(b.el('message').hidden, true, 'A successful poll clears the stale notice');
});

test('a status for another workload or with invalid values is an unexpected response', async () => {
  for (const body of [status({ workload: 'product-reader' }), status({ targetRate: '0.5' }), status({ state: 'BUSY' }),
    status({ traffic: [{ category: 'other', requests: 1, retries: 0, errors: 0 }] }), status({ requests: -1 }), null]) {
    const b = await browser(producerPage, { respond: () => body });
    assert.equal(b.el('state').hidden, false);
    assert.ok(b.el('state-description').textContent.startsWith('The workload returned an unexpected status.'));
  }
});

test('the concurrency notice explains why the achieved rate stays below the target', async () => {
  const b = await browser(producerPage, { respond: () => status({ targetRate: 2, effectiveRate: 2,
    limit: { code: 'CONCURRENCY', concurrency: 1, meanOperationSeconds: 1.234, achievableRate: 0.81 } }) });
  assert.equal(b.el('limit').hidden, false);
  assert.equal(b.el('limit').textContent, 'The target is above what the bounded concurrency can reach: 1 in flight, '
    + '1.234 s per operation on average, thus at most 0.81 new order starts/s.');
});

test('the seeder page starts one run, shows its progress and the literal result, and reports a conflict', async () => {
  const seeder = overrides => status({ workload: 'product-seeder', unit: 'HTTP requests/s', state: 'IDLE', targetRate: 100,
    effectiveRate: 100, defaultRate: 100, maximumRate: 1000, rateStep: 10, inFlight: 0,
    traffic: [{ category: 'creation', requests: 0, retries: 0, errors: 0 }, { category: 'verification', requests: 0, retries: 0, errors: 0 }],
    ...overrides });
  const run = { number: 1, state: 'RUNNING', startedAt: '2026-10-22T10:15:30.123Z', finishedAt: null,
    completedOperations: 352, plannedOperations: 1056, summary: null, error: null };
  let started = false;
  const b = await browser(seederPage, { respond: call => {
    if (call.path.endsWith('/runs')) {
      if (started) return { code: 409, body: { code: 'RUN_ACTIVE', message: 'A seed run is already in progress.' } };
      started = true;
      return { code: 202, body: seeder({ state: 'RUNNING', run }) };
    }
    return started ? seeder({ state: 'FAILED', run: { ...run, state: 'FAILED', finishedAt: '2026-10-22T10:16:00Z',
      error: '<script>Create seller failed</script>' } }) : seeder();
  } });
  assert.equal(b.el('run-number').textContent, 'No run yet');
  assert.equal(b.el('run-start').disabled, false);
  b.el('run-start').fire();
  await flush();
  assert.equal(b.calls.at(-1).path, '/api/v1/product-seeder/control/runs');
  assert.equal(b.el('message').textContent, 'Seed run 1 started.');
  assert.equal(b.el('run-progress').textContent, '352 of 1,056 operations');
  assert.equal(b.el('run-start').disabled, true, 'One run at a time');
  await b.poll();
  assert.equal(b.el('run-state').textContent, 'FAILED');
  assert.equal(b.el('run-result').textContent, '<script>Create seller failed</script>');
  assert.equal(b.el('run-times').textContent, '2026-10-22T10:15:30.123Z → 2026-10-22T10:16:00Z');
  b.el('run-start').fire();
  await flush();
  assert.equal(b.el('message').textContent, 'Not applied: A seed run is already in progress.');
});

test('each page names its own workload title, API and unit', () => {
  for (const [page, workload, unit, title] of [[producerPage, 'order-producer', 'new order starts/s', 'Workload Order Producer'],
    [readerPage, 'product-reader', 'product read starts/s', 'Workload Product Reader'],
    [seederPage, 'product-seeder', 'HTTP requests/s', 'Workload Product Seeder']]) {
    assert.ok(page.includes(`<title>${title} · ShardShop</title>`) && page.includes(`<h1>${title}</h1>`), title);
    assert.ok(page.includes(`data-workload="${workload}" data-api="/api/v1/${workload}/control"`), workload);
    assert.ok(page.includes(`(${unit})`), unit);
    assert.ok(page.includes('/workload-control/control.js?v='), workload);
  }
});
