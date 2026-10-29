-- 세션과 계정별 인증 가드를 쓰지 않고 읽어 판정 하나를 돌려준다. 계약은 docs/data-model.md#redis-인증-데이터.
-- KEYS[1] = commerce-settle:auth:session:<epoch>:<tokenHash>, ARGV[1] = epoch
-- AUTHENTICATED:<actorKind>:<actorId> | UNAUTHENTICATED(401) | UNAVAILABLE(503)
local session = redis.call('HMGET', KEYS[1], 'actorKind', 'actorId', 'purpose', 'authVersion', 'expiresAt')
local kind, id, purpose = session[1], session[2], session[3]
local version, expires_at = tonumber(session[4]), tonumber(session[5])

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
local pending = guard[3]
if not guard[2] or (pending and pending ~= '') then
  return 'UNAVAILABLE'
end
if guard[2] == 'BLOCKED' then
  return 'UNAUTHENTICATED'
end
if guard[2] ~= 'ACTIVE' or not tonumber(guard[1]) then
  return 'UNAVAILABLE'
end
if tonumber(guard[1]) ~= version then
  return 'UNAUTHENTICATED'
end
return 'AUTHENTICATED:' .. kind .. ':' .. id
