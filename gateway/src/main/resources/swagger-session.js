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
  let details = null;
  let generation = 0;
  let loginAttempt = 0;
  let timer;
  let ui;
  let system;
  let applyingLogin = false;
  let clearingAuthorization = false;
  let status;
  let message = configuration.fixtures
    ? '개발용 계정을 선택하고 로그인하세요.'
    : '개발용 계정 로그인이 꺼져 있습니다. 서버의 fixture 설정을 확인해 주세요.';

  function currentToken() {
    const value = system?.authSelectors.authorized().getIn(['accountSession', 'value']);
    return typeof value === 'string' && value ? value : null;
  }

  function render() {
    if (!status) return;
    const token = currentToken();
    status.textContent = token && details
      ? `${details.actorKind === 'BUYER' ? '구매자' : '판매자'} ${details.displayName} · 로그인 연결됨 · 만료 ${new Date(details.expiresAt).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })} KST · 새로고침하면 다시 로그인합니다.`
      : token ? '인증 연결됨 · 유효 여부는 요청 시 확인합니다. 새로고침하면 다시 로그인합니다.'
        : message;
  }

  function reset(reason) {
    details = null;
    generation += 1;
    window.clearTimeout(timer);
    message = reason;
    render();
  }

  function clear(reason) {
    if (system) {
      clearingAuthorization = true;
      try { system.authActions.logout(['accountSession']); }
      finally { clearingAuthorization = false; }
    }
    reset(reason);
  }

  function expire() {
    if (details && Date.now() >= details.expiresAt) clear('세션이 만료됐습니다. 다시 로그인해 주세요.');
  }

  function acceptLogin(body, actor, attempt) {
    if (attempt !== loginAttempt || !body || body.tokenType !== 'Bearer'
      || typeof body.token !== 'string' || !/^[\x21-\x7e]{32,512}$/.test(body.token)
      || !body.account || body.account.actorKind !== actor || body.account.status !== 'ACTIVE'
      || typeof body.account.displayName !== 'string' || !body.account.displayName.trim()) return;
    const expiresAt = Date.parse(body.expiresAt);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) return;
    applyingLogin = true;
    try { ui.preauthorizeApiKey('accountSession', body.token); }
    finally { applyingLogin = false; }
    if (currentToken() !== body.token) return;
    window.clearTimeout(timer);
    details = { actorKind: actor, displayName: body.account.displayName, expiresAt };
    generation += 1;
    timer = window.setTimeout(expire, Math.min(expiresAt - Date.now(), 2147483647));
    render();
    return true;
  }

  function fixtureLogin(section) {
    const form = document.createElement('form');
    form.id = 'swagger-fixture-login';
    form.className = 'wrapper';
    Object.assign(form.style, { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '12px', padding: '0 20px 16px' });
    const label = document.createElement('label');
    label.textContent = '개발용 계정 ';
    Object.assign(label.style, { color: 'inherit', fontSize: '14px', lineHeight: '20px', margin: '0', display: 'flex', alignItems: 'center', gap: '8px' });
    const select = document.createElement('select');
    select.id = 'swagger-fixture-account';
    select.setAttribute('aria-label', '개발용 계정');
    Object.assign(select.style, { height: '44px', boxSizing: 'border-box', lineHeight: '20px', backgroundSize: '16px 16px' });
    for (const [value, text] of [['BUYER_A', '구매자 A'], ['BUYER_B', '구매자 B'], ['SELLER_A', '판매자 A'], ['SELLER_B', '판매자 B']]) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = text;
      select.appendChild(option);
    }
    const button = document.createElement('button');
    button.type = 'submit';
    button.className = 'btn execute';
    button.textContent = '로그인';
    Object.assign(button.style, { height: '44px', boxSizing: 'border-box', lineHeight: '20px', display: 'inline-flex', alignItems: 'center', justifyContent: 'center' });
    const feedback = document.createElement('span');
    feedback.setAttribute('role', 'status');
    label.appendChild(select);
    form.appendChild(label);
    form.appendChild(button);
    form.appendChild(feedback);
    form.onsubmit = async event => {
      event.preventDefault();
      if (button.disabled) return;
      const account = select.value;
      const actor = account.startsWith('BUYER_') ? 'BUYER' : 'SELLER';
      const attempt = ++loginAttempt;
      button.disabled = true;
      select.disabled = true;
      button.textContent = '로그인 중…';
      feedback.textContent = '';
      message = '로그인 중입니다.';
      render();
      try {
        const response = await nativeFetch('/swagger-ui/fixture-sessions', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ account }), credentials: 'same-origin', redirect: 'error',
        });
        if (attempt !== loginAttempt) return;
        if (response.status === 200) {
          if (!acceptLogin(await response.json(), actor, attempt)) {
            feedback.textContent = '로그인 응답을 확인할 수 없습니다. 다시 로그인해 주세요.';
          }
        } else {
          feedback.textContent = response.status === 401
            ? '개발용 계정을 확인해 주세요. 계정 적재 여부와 최초 fixture 비밀번호가 일치해야 합니다.'
            : response.status === 429 ? '로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.'
              : '로그인할 수 없습니다. 서버 상태를 확인하고 다시 시도해 주세요.';
        }
      } catch (_) {
        if (attempt === loginAttempt) feedback.textContent = '로그인 응답을 확인할 수 없습니다. 서버 상태를 확인하고 다시 시도해 주세요.';
      } finally {
        button.disabled = false;
        select.disabled = false;
        button.textContent = '로그인';
        if (attempt === loginAttempt && !currentToken()) message = '계정을 선택하고 다시 로그인하세요.';
        render();
      }
    };
    section.appendChild(form);
  }

  return {
    plugin(instance) {
      system = instance;
      return {
        wrapComponents: {
          authorizeBtn: () => () => null,
          authorizeOperationBtn: () => () => null,
          authorizationPopup: () => () => null,
        },
        statePlugins: {
          auth: {
            wrapActions: {
              restoreAuthorization: next => values => {
                // Swagger's document selector clears in-memory auth. Keep this shared session
                // in the same auth store; other schemes still follow Swagger's reset.
                if (Object.keys(values.authorized).length === 0 && currentToken()) {
                  const session = system.authSelectors.authorized().get('accountSession').toJS();
                  return next({ ...values, authorized: { accountSession: session } });
                }
                return next(values);
              },
              authorize: next => values => {
                const result = next(values);
                if (Object.hasOwn(values, 'accountSession') && !applyingLogin) {
                  loginAttempt += 1;
                  reset('로그인 정보가 변경됐습니다. 계정을 확인해 주세요.');
                }
                return result;
              },
              logout: next => names => {
                const result = next(names);
                if (names.includes('accountSession') && !clearingAuthorization) {
                  loginAttempt += 1;
                  reset('Swagger 인증 연결을 해제했습니다. 다시 로그인해 주세요.');
                }
                return result;
              },
            },
          },
        },
      };
    },

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
        if (configuration.fixtures) fixtureLogin(section);
        const root = document.getElementById('swagger-ui');
        root.before(section);
      }
      expire();
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
      const token = protectedRequest ? currentToken() : null;
      if (token) request.headers.Authorization = `Bearer ${token}`;
      const requestGeneration = generation;
      const actor = operation && method === 'POST'
        && Object.keys(configuration.login).find(kind => configuration.login[kind] === url.pathname);
      const logout = operation && method === 'DELETE' && url.pathname === configuration.logout;
      const attempt = actor || logout ? ++loginAttempt : 0;
      if (token || actor) request.redirect = 'error';
      request.userFetch = async (input, options) => {
        const response = await nativeFetch(input, options);
        if (actor && response.status === 200) {
          try { acceptLogin(await response.clone().json(), actor, attempt); }
          catch (_) { /* An invalid login response must not replace a valid session. */ }
        } else if (token && requestGeneration === generation && currentToken() === token) {
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
