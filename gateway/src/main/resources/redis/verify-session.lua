-- 세션과 계정별 인증 가드를 쓰지 않고 읽어 판정 하나를 돌려준다. 계약은 docs/data-model.md#redis-인증-데이터.
-- KEYS[1] = commerce-settle:auth:session:<epoch>:<tokenHash>, ARGV[1] = epoch
-- AUTHENTICATED:<actorKind>:<actorId> | UNAUTHENTICATED(401) | UNAVAILABLE(503)

-- tonumber는 nan·inf·16진·지수 표기도 받으므로 10진 정수열만 숫자로 읽는다. 15자리까지는 double로 정확하다.
local function integer(value)
  if value and #value <= 15 and value:match('^%d+$') then
    return tonumber(value)
  end
end

local session = redis.call('HMGET', KEYS[1], 'actorKind', 'actorId', 'purpose', 'authVersion', 'expiresAt')
local kind, id, purpose = session[1], session[2], session[3]
local version, expires_at = integer(session[4]), integer(session[5])

if not (kind or id or purpose or session[4] or session[5]) then
  return 'UNAUTHENTICATED'
end
if purpose and purpose ~= 'SESSION' then
  return 'UNAUTHENTICATED'
end
if not purpose or (kind ~= 'BUYER' and kind ~= 'SELLER') or not id or not version or not expires_at then
  return 'UNAVAILABLE'
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now >= expires_at then
  return 'UNAUTHENTICATED'
end

local guard = redis.call('HMGET', 'commerce-settle:auth:subject:' .. ARGV[1] .. ':' .. kind .. ':' .. id,
  'authVersion', 'state', 'pendingChangeId')
local guard_version, pending = integer(guard[1]), guard[3]
if not guard[2] or (pending and pending ~= '') then
  return 'UNAVAILABLE'
end
if guard[2] == 'BLOCKED' then
  return 'UNAUTHENTICATED'
end
if guard[2] ~= 'ACTIVE' or not guard_version then
  return 'UNAVAILABLE'
end
if guard_version ~= version then
  return 'UNAUTHENTICATED'
end
return 'AUTHENTICATED:' .. kind .. ':' .. id
