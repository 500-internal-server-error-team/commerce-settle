// This factory is scoped to Swagger's initializer. Credentials live only in this tab's memory.
function createSwaggerSession(configuration) {
  const nativeFetch = window.fetch.bind(window);
  const origin = window.location.origin;
  const operations = configuration.operations.map(operation => ({
    ...operation,
    pattern: new RegExp('^' + operation.path.split('/').map(segment =>
      /^\{[^}]+\}$/.test(segment) ? '[^/]+' : segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    ).join('/') + '$'),
  }));
  let active = null;
  let generation = 0;
  let loginAttempt = 0;
  let timer;
  let ui;
  let status;
  let message = '로그인 전 · Sessions에서 구매자 또는 판매자로 로그인하면 인증을 자동 연결합니다.';

  function render() {
    if (!status) return;
    status.textContent = active
      ? `${active.actorKind === 'BUYER' ? '구매자' : '판매자'} ${active.displayName} · 로그인 연결됨 · 만료 ${new Date(active.expiresAt).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })} KST · 새로고침하면 다시 로그인합니다.`
      : message;
  }

  function connect() {
    if (ui && active) ui.preauthorizeApiKey('accountSession', active.token);
  }

  function clear(reason) {
    active = null;
    generation += 1;
    window.clearTimeout(timer);
    message = reason;
    if (ui) ui.authActions.logout(['accountSession']);
    render();
  }

  function expire() {
    if (active && Date.now() >= active.expiresAt) clear('세션이 만료됐습니다. 다시 로그인해 주세요.');
  }

  function acceptLogin(body, actor, attempt) {
    if (attempt !== loginAttempt || !body || body.tokenType !== 'Bearer'
      || typeof body.token !== 'string' || !/^[\x21-\x7e]{32,512}$/.test(body.token)
      || !body.account || body.account.actorKind !== actor || body.account.status !== 'ACTIVE'
      || typeof body.account.displayName !== 'string' || !body.account.displayName.trim()) return;
    const expiresAt = Date.parse(body.expiresAt);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) return;
    window.clearTimeout(timer);
    active = { token: body.token, actorKind: actor, displayName: body.account.displayName,
      expiresAt, generation: ++generation };
    timer = window.setTimeout(expire, Math.min(expiresAt - Date.now(), 2147483647));
    connect();
    render();
  }

  return {
    attach(instance) {
      ui = instance;
      if (!status) {
        const section = document.createElement('section');
        section.className = 'swagger-ui';
        section.setAttribute('aria-label', 'Swagger 로그인 상태');
        status = document.createElement('p');
        status.className = 'wrapper';
        status.setAttribute('role', 'status');
        status.setAttribute('aria-live', 'polite');
        status.style.padding = '12px 20px';
        section.appendChild(status);
        const root = document.getElementById('swagger-ui');
        root.before(section);
      }
      expire();
      connect();
      render();
    },

    request(request) {
      expire();
      const url = new URL(request.url, origin);
      const method = (request.method || 'GET').toUpperCase();
      const operation = url.origin === origin && operations.find(candidate =>
        candidate.method === method && candidate.pattern.test(url.pathname));
      const protectedRequest = operation && !operation.actors.includes('PUBLIC');
      request.headers = request.headers || {};
      // Swagger's auth store also adds headers. Remove them before applying the canonical boundary.
      for (const key of Object.keys(request.headers)) {
        if (key.toLowerCase() === 'authorization') delete request.headers[key];
      }
      const session = protectedRequest ? active : null;
      if (session) request.headers.Authorization = `Bearer ${session.token}`;
      const actor = operation && method === 'POST'
        && Object.keys(configuration.login).find(kind => configuration.login[kind] === url.pathname);
      const logout = operation && method === 'DELETE' && url.pathname === configuration.logout;
      const attempt = actor || logout ? ++loginAttempt : 0;
      if (session || actor) request.redirect = 'error';
      request.userFetch = async (input, options) => {
        const response = await nativeFetch(input, options);
        if (actor && response.status === 200) {
          try { acceptLogin(await response.clone().json(), actor, attempt); }
          catch (_) { /* An invalid login response must not replace a valid session. */ }
        } else if (session && active && session.generation === active.generation) {
          if (response.status === 401) clear('서버에서 인증을 거절했습니다. 다시 로그인해 주세요.');
          else if (logout && response.status === 204) {
            clear('로그아웃됐습니다. 보호 API를 사용하려면 다시 로그인해 주세요.');
          }
        }
        return response;
      };
      return request;
    },
  };
}
