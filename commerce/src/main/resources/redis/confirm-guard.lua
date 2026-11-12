-- DB에 커밋한 인증 변경을 같은 변경 ID의 가드에만 반영한다. 다른 변경이나 이전 버전의 지연 반영은 거절한다.
-- 계약은 docs/data-model.md#계정별-인증-가드.
-- KEYS[1] = commerce-settle:auth:subject:<epoch>:<actorKind>:<actorId>
-- ARGV[1] = changeId, ARGV[2] = DB에서 확정한 authVersion, ARGV[3] = ACTIVE | BLOCKED
-- CONFIRMED | UNAVAILABLE(503)

-- tonumber는 nan·inf·16진·지수 표기도 받으므로 10진 정수열만 숫자로 읽는다. 15자리까지는 double로 정확하다.
local function integer(value)
  if value and #value <= 15 and value:match('^%d+$') then
    return tonumber(value)
  end
end

local version = integer(ARGV[2])
if not version or not ARGV[1] or ARGV[1] == '' or (ARGV[3] ~= 'ACTIVE' and ARGV[3] ~= 'BLOCKED') then
  return redis.error_reply('invalid guard arguments')
end

local guard = redis.call('HMGET', KEYS[1], 'authVersion', 'state', 'pendingChangeId')
local guard_version = integer(guard[1])
if guard[3] ~= ARGV[1] or guard[2] ~= 'BLOCKED' or not guard_version or version <= guard_version then
  return 'UNAVAILABLE'
end

redis.call('HSET', KEYS[1], 'authVersion', ARGV[2], 'state', ARGV[3])
redis.call('HDEL', KEYS[1], 'pendingChangeId')
return 'CONFIRMED'
