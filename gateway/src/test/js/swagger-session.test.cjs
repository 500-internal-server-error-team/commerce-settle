const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/swagger-session.js'), 'utf8');
const contract = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../docs/openapi.json'), 'utf8'));
const operations = Object.entries(contract.paths).filter(([name]) => name.startsWith('/api/'))
  .flatMap(([name, methods]) => Object.entries(methods).filter(([, op]) => op['x-actor'])
    .map(([method, op]) => ({ method: method.toUpperCase(), path: name, actors: op['x-actor'].split('|') })));
const configuration = { operations, login: { BUYER: '/api/buyer-sessions', SELLER: '/api/seller-sessions' },
  logout: '/api/sessions/current' };
const buyer = 'buyer-session-' + 'a'.repeat(32);
const seller = 'seller-session-' + 'b'.repeat(32);

function fixture() {
  let now = Date.parse('2026-11-06T12:00:00+09:00');
  const replies = [];
  const calls = [];
  const authorizations = [];
  const logouts = [];
  const timers = new Map();
  const elements = [];
  const authorized = new Map();
  class Clock extends Date { static now() { return now; } }
  const window = {
    location: { origin: 'http://localhost:8080' },
    fetch: async (url, options) => { calls.push({ url, options }); return await replies.shift(); },
    setTimeout: callback => { const id = Symbol(); timers.set(id, callback); return id; },
    clearTimeout: id => timers.delete(id),
  };
  for (const name of ['localStorage', 'sessionStorage']) {
    Object.defineProperty(window, name, { get() { throw new Error('Persistent credentials are forbidden'); } });
  }
  const document = {
    createElement() {
      const element = { style: {}, children: [], setAttribute() {},
        appendChild(child) { this.children.push(child); } };
      Object.defineProperty(element, 'innerHTML', { set() { throw new Error('Use textContent'); } });
      return element;
    },
    getElementById: () => ({ before: element => elements.push(element) }),
  };
  const create = vm.runInNewContext(source + '\ncreateSwaggerSession', { window, document, URL, Date: Clock });
  const client = create(configuration);
  const ui = {
    preauthorizeApiKey(name, token) {
      authorizations.push({ name, token });
      return ui.authActions.authorize({ [name]: { value: token } });
    },
    authActions: {
      authorize(values) { for (const [name, value] of Object.entries(values)) authorized.set(name, value); },
      logout(names) { logouts.push([...names]); for (const name of names) authorized.delete(name); },
      restoreAuthorization({ authorized: values }) {
        authorized.clear();
        for (const [name, value] of Object.entries(values)) authorized.set(name, value);
      },
    },
    authSelectors: { authorized: () => ({
      getIn: ([name, field]) => authorized.get(name)?.[field],
      get: name => ({ toJS: () => ({ ...authorized.get(name) }) }),
    }) },
  };
  const wrappers = client.plugin(ui).statePlugins.auth.wrapActions;
  for (const [name, wrap] of Object.entries(wrappers)) ui.authActions[name] = wrap(ui.authActions[name]);
  ui.authActions.authorizeWithPersistOption = values => ui.authActions.authorize(values);
  client.attach(ui);
  function request(method, url, headers = {}) {
    return client.request({ method, url, headers });
  }
  async function send(method, url, status = 200, body = {}, headers = {}) {
    replies.push(new Response(status === 204 ? null : JSON.stringify(body), { status }));
    const req = request(method, url, headers);
    await req.userFetch(req.url, req);
    return req;
  }
  function session(actor = 'BUYER', token = buyer, extra = {}) {
    return { token, tokenType: 'Bearer', expiresAt: new Date(now + 1800000).toISOString(),
      account: { id: '00000000-0000-4000-8000-000000000001', actorKind: actor, status: 'ACTIVE', displayName: '테스트 계정' }, ...extra };
  }
  async function login(actor = 'BUYER', token = buyer) { await send('POST', configuration.login[actor], 200, session(actor, token)); }
  return { client, ui, request, send, session, login, replies, calls, authorizations, logouts, timers, authorized,
    advance: milliseconds => { now += milliseconds; }, status: () => elements[0].children[0].textContent };
}

test('successful login authorizes canonical user operations across all three services without copying a token', async () => {
  const f = fixture();
  await f.login();
  assert.equal(f.authorizations.at(-1).name, 'accountSession');
  assert.match(f.status(), /구매자.*로그인 연결됨/);
  for (const operation of operations.filter(op => !op.actors.includes('PUBLIC'))) {
    const url = operation.path.replace(/\{[^}]+\}/g, '00000000-0000-4000-8000-000000000001');
    assert.equal(f.request(operation.method, url).headers.Authorization, `Bearer ${buyer}`, url);
  }
  f.client.attach(f.ui);
  assert.equal(f.authorizations.length, 1);
  assert.equal(f.authorizations.at(-1).token, buyer);
});

test('never carries a user token to public, unknown, internal, or other-origin requests', async () => {
  const f = fixture();
  await f.login();
  for (const [method, url] of [
    ['GET', '/v3/api-docs/payment'], ['GET', '/api/products'], ['POST', '/api/buyer-sessions'],
    ['GET', '/internal/payment-jobs'], ['GET', '/api/not-declared'], ['PUT', '/api/orders'],
    ['POST', 'https://example.test/api/orders'], ['POST', '//example.test/api/orders'],
  ]) {
    const req = f.request(method, url, { authorization: `Bearer ${buyer}` });
    assert.equal(Object.keys(req.headers).some(key => key.toLowerCase() === 'authorization'), false, url);
  }
  assert.equal(f.request('POST', '/api/orders').redirect, 'error');
});

test('a successful account switch replaces the active token and failed login preserves it', async () => {
  const f = fixture();
  await f.login();
  await f.send('POST', '/api/seller-sessions', 401);
  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${buyer}`);
  await f.login('SELLER', seller);
  assert.match(f.status(), /판매자.*로그인 연결됨/);
  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${seller}`);
});

test('manual Authorize supplies the credential on a fresh page and across document changes', () => {
  const f = fixture();

  f.ui.authActions.authorizeWithPersistOption({ accountSession: { value: seller } });
  f.ui.authActions.restoreAuthorization({ authorized: {} });
  f.client.attach(f.ui);

  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${seller}`);
  assert.match(f.status(), /수동 토큰 연결됨/);
  assert.equal(f.authorizations.length, 0);
  assert.equal(f.timers.size, 0);
});

test('document selection preserves only the shared account session and keeps its expiry', async () => {
  const f = fixture();
  await f.login();
  f.ui.authActions.authorize({ serviceCredential: { value: 'unrelated' } });

  f.ui.authActions.restoreAuthorization({ authorized: {} });
  f.client.attach(f.ui);

  assert.equal(f.request('GET', '/api/buyers/me/mileage').headers.Authorization, `Bearer ${buyer}`);
  assert.equal(f.authorized.has('serviceCredential'), false);
  assert.match(f.status(), /구매자.*로그인 연결됨/);
  assert.equal(f.timers.size, 1);
  f.advance(1800000);
  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, undefined);
  assert.equal(f.authorized.has('accountSession'), false);

  f.ui.authActions.restoreAuthorization({ authorized: {} });
  f.client.attach(f.ui);
  assert.equal(f.authorized.has('accountSession'), false);
});

test('manual credential replacement drops old account details and expiry without restoring the old token', async () => {
  const f = fixture();
  await f.login();
  const oldTimers = [...f.timers.values()];

  f.ui.authActions.authorizeWithPersistOption({ accountSession: { value: seller } });
  f.client.attach(f.ui);
  f.advance(1800000);
  for (const callback of oldTimers) callback();

  assert.equal(f.request('POST', '/api/orders', { authorization: `Bearer ${buyer}` }).headers.Authorization,
    `Bearer ${seller}`);
  assert.match(f.status(), /수동 토큰 연결됨/);
  assert.doesNotMatch(f.status(), /테스트 계정/);
  assert.equal(f.timers.size, 0);
  assert.equal(f.authorizations.length, 1);
});

test('manual Authorize invalidates an older pending API login', async () => {
  const f = fixture();
  let resolve;
  f.replies.push(new Promise(done => { resolve = done; }));
  const login = f.request('POST', '/api/buyer-sessions');
  const pending = login.userFetch(login.url, login);

  f.ui.authActions.authorizeWithPersistOption({ accountSession: { value: seller } });
  resolve(new Response(JSON.stringify(f.session()), { status: 200 }));
  await pending;

  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${seller}`);
  assert.match(f.status(), /수동 토큰 연결됨/);
});

test('a late rejection cannot remove a manually selected credential', async () => {
  const f = fixture();
  await f.login();
  let resolve;
  f.replies.push(new Promise(done => { resolve = done; }));
  const old = f.request('GET', '/api/accounts/me');
  const pending = old.userFetch(old.url, old);

  f.ui.authActions.authorizeWithPersistOption({ accountSession: { value: seller } });
  resolve(new Response('{}', { status: 401 }));
  await pending;

  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${seller}`);
});

test('manual credentials are cleared on current rejection or successful server logout', async () => {
  for (const [method, url, status] of [['GET', '/api/accounts/me', 401], ['DELETE', '/api/sessions/current', 204]]) {
    const f = fixture();
    f.ui.authActions.authorizeWithPersistOption({ accountSession: { value: seller } });

    await f.send(method, url, status);

    assert.equal(f.authorized.has('accountSession'), false);
    assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, undefined);
    assert.doesNotMatch(f.status(), /수동 토큰 연결됨/);
    assert.equal(f.calls.length, 1);
  }
});

test('invalid successful-login payloads do not replace a valid session', async () => {
  const f = fixture();
  await f.login();
  for (const body of [f.session('SELLER'), f.session('BUYER', ''), f.session('BUYER', buyer, { tokenType: 'Basic' }),
    f.session('BUYER', buyer, { expiresAt: 'invalid' }), f.session('BUYER', buyer, { expiresAt: '2020-01-01T00:00:00Z' })]) {
    await f.send('POST', '/api/buyer-sessions', 200, body);
    assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${buyer}`);
  }
});

for (const status of [403, 503]) {
  test(`protected ${status} keeps the current session`, async () => {
    const f = fixture();
    await f.login();
    await f.send('POST', '/api/orders', status);
    assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${buyer}`);
  });
}

test('current-session 401 and successful logout clear the token without retrying a business request', async () => {
  for (const [method, path, status] of [['GET', '/api/accounts/me', 401], ['DELETE', '/api/sessions/current', 204]]) {
    const f = fixture();
    await f.login();
    await f.send(method, path, status);
    assert.equal(f.calls.length, 2);
    assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, undefined);
    assert.deepEqual(f.logouts.at(-1), ['accountSession']);
  }
});

test('a late rejection of an old session cannot clear a newer login', async () => {
  const f = fixture();
  await f.login();
  let resolve;
  f.replies.push(new Promise(done => { resolve = done; }));
  const old = f.request('GET', '/api/accounts/me');
  const pending = old.userFetch(old.url, old);
  await f.login('SELLER', seller);
  resolve(new Response('{}', { status: 401 }));
  await pending;
  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${seller}`);
});

test('Swagger native logout clears the active session across document changes', async () => {
  const f = fixture();
  await f.login();
  f.client.attach(f.ui);
  const before = f.logouts.length;

  f.ui.authActions.logout(['accountSession']);
  f.client.attach(f.ui);

  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, undefined);
  assert.match(f.status(), /인증 연결을 해제/);
  assert.equal(f.logouts.length, before + 1);
  assert.equal(f.timers.size, 0);
});

test('Swagger native logout invalidates pending login responses with or without an active account', async () => {
  for (const signedIn of [false, true]) {
    const f = fixture();
    if (signedIn) await f.login();
    let resolve;
    f.replies.push(new Promise(done => { resolve = done; }));
    const login = f.request('POST', '/api/seller-sessions');
    const pending = login.userFetch(login.url, login);

    f.ui.authActions.logout(['accountSession']);
    resolve(new Response(JSON.stringify(f.session('SELLER', seller)), { status: 200 }));
    await pending;

    assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, undefined);
    assert.doesNotMatch(f.status(), /로그인 연결됨/);
  }
});

test('other authorization schemes do not remove the account session and login works after native logout', async () => {
  const f = fixture();
  await f.login();

  f.ui.authActions.authorizeWithPersistOption({ anotherScheme: { value: 'other' } });
  f.ui.authActions.logout(['anotherScheme']);
  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${buyer}`);
  assert.match(f.status(), /테스트 계정/);
  f.ui.authActions.logout(['accountSession']);
  await f.login('SELLER', seller);

  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${seller}`);
  assert.match(f.status(), /판매자.*로그인 연결됨/);
});

test('old-session rejection or expiry does not discard a newer login that is still pending', async () => {
  for (const invalidation of ['rejection', 'expiry']) {
    const f = fixture();
    await f.login();
    let resolve;
    f.replies.push(new Promise(done => { resolve = done; }));
    const login = f.request('POST', '/api/seller-sessions');
    const pending = login.userFetch(login.url, login);

    if (invalidation === 'rejection') await f.send('GET', '/api/accounts/me', 401);
    else {
      f.advance(1800000);
      f.request('GET', '/api/accounts/me');
    }
    resolve(new Response(JSON.stringify(f.session('SELLER', seller)), { status: 200 }));
    await pending;

    assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${seller}`);
  }
});

test('logout cancels an older pending login and cannot resurrect its token', async () => {
  const f = fixture();
  await f.login();
  let resolve;
  f.replies.push(new Promise(done => { resolve = done; }));
  const old = f.request('POST', '/api/seller-sessions');
  const pending = old.userFetch(old.url, old);
  await f.send('DELETE', '/api/sessions/current', 204);
  resolve(new Response(JSON.stringify(f.session('SELLER', seller)), { status: 200 }));
  await pending;
  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, undefined);
});

test('a server logout response preserves a newer pending login', async () => {
  const f = fixture();
  await f.login();
  let finishLogout;
  f.replies.push(new Promise(done => { finishLogout = done; }));
  const logout = f.request('DELETE', '/api/sessions/current');
  const pendingLogout = logout.userFetch(logout.url, logout);
  let finishLogin;
  f.replies.push(new Promise(done => { finishLogin = done; }));
  const login = f.request('POST', '/api/seller-sessions');
  const pendingLogin = login.userFetch(login.url, login);

  finishLogout(new Response(null, { status: 204 }));
  await pendingLogout;
  finishLogin(new Response(JSON.stringify(f.session('SELLER', seller)), { status: 200 }));
  await pendingLogin;

  assert.equal(f.request('GET', '/api/accounts/me').headers.Authorization, `Bearer ${seller}`);
  assert.match(f.status(), /판매자.*로그인 연결됨/);
});

test('fixed expiry and a fresh page require login again', async () => {
  const f = fixture();
  await f.login();
  f.advance(1800000);
  for (const callback of [...f.timers.values()]) callback();
  assert.match(f.status(), /만료/);
  assert.equal(f.request('POST', '/api/orders').headers.Authorization, undefined);
  assert.equal(fixture().request('POST', '/api/orders').headers.Authorization, undefined);
});
