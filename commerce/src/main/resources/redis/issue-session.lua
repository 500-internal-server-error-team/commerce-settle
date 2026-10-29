-- 계정별 인증 가드가 DB에서 읽은 인증 버전의 ACTIVE일 때만 로그인 세션을 저장한다. 계약은 docs/data-model.md#redis-인증-데이터.
-- 판정은 Gateway의 verify-session.lua와 같고, 조건을 모두 확인한 뒤 마지막에 쓴다.
-- KEYS[1] = commerce-settle:auth:session:<epoch>:<tokenHash>, KEYS[2] = commerce-settle:auth:subject:<epoch>:<actorKind>:<actorId>
-- ARGV[1] = actorKind, ARGV[2] = actorId, ARGV[3] = authVersion, ARGV[4] = 세션 수명(ms)
-- ISSUED:<expiresAt> | REJECTED(401) | UNAVAILABLE(503)

-- tonumber는 nan·inf·16진·지수 표기도 받으므로 10진 정수열만 숫자로 읽는다. 15자리까지는 double로 정확하다.
local function integer(value)
  if value and #value <= 15 and value:match('^%d+$') then
    return tonumber(value)
  end
end

local version, lifetime = integer(ARGV[3]), integer(ARGV[4])
if not version or not lifetime then
  return redis.error_reply('invalid session arguments')
end
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 'UNAVAILABLE'
end

-- 필수 필드가 모두 빠졌어도 키가 있으면 손상된 가드이므로 새로 만들지 않고 아래에서 UNAVAILABLE로 판정한다.
local missing = redis.call('EXISTS', KEYS[2]) == 0
if not missing then
  local guard = redis.call('HMGET', KEYS[2], 'authVersion', 'state', 'pendingChangeId')
  local guard_version, pending = integer(guard[1]), guard[3]
  if not guard[2] or (pending and pending ~= '') then
    return 'UNAVAILABLE'
  end
  if guard[2] == 'BLOCKED' then
    return 'REJECTED'
  end
  if guard[2] ~= 'ACTIVE' or not guard_version then
    return 'UNAVAILABLE'
  end
  if guard_version ~= version then
    return 'REJECTED'
  end
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local issued_at, expires_at = string.format('%.0f', now), string.format('%.0f', now + lifetime)
if missing then
  redis.call('HSET', KEYS[2], 'authVersion', ARGV[3], 'state', 'ACTIVE')
end
redis.call('HSET', KEYS[1], 'actorKind', ARGV[1], 'actorId', ARGV[2], 'purpose', 'SESSION',
  'authVersion', ARGV[3], 'issuedAt', issued_at, 'expiresAt', expires_at)
redis.call('PEXPIREAT', KEYS[1], expires_at)
return 'ISSUED:' .. expires_at
