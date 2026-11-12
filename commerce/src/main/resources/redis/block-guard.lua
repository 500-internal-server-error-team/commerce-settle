-- 계정 인증 변경 전에 가드를 변경 중 BLOCKED로 바꿔 같은 계정의 세션 검증·발급과 다른 변경을 막는다.
-- 계약은 docs/data-model.md#계정별-인증-가드. 판정은 issue-session.lua와 같고, 조건을 모두 확인한 뒤 마지막에 쓴다.
-- KEYS[1] = commerce-settle:auth:subject:<epoch>:<actorKind>:<actorId>
-- ARGV[1] = changeId, ARGV[2] = DB에서 읽은 authVersion
-- BLOCKED | REJECTED(401) | UNAVAILABLE(503)

-- tonumber는 nan·inf·16진·지수 표기도 받으므로 10진 정수열만 숫자로 읽는다. 15자리까지는 double로 정확하다.
local function integer(value)
  if value and #value <= 15 and value:match('^%d+$') then
    return tonumber(value)
  end
end

local version = integer(ARGV[2])
if not version or not ARGV[1] or ARGV[1] == '' then
  return redis.error_reply('invalid guard arguments')
end

-- 필수 필드가 모두 빠졌어도 키가 있으면 손상된 가드이므로 덮어쓰지 않고 아래에서 UNAVAILABLE로 판정한다.
if redis.call('EXISTS', KEYS[1]) == 1 then
  local guard = redis.call('HMGET', KEYS[1], 'authVersion', 'state', 'pendingChangeId')
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

redis.call('HSET', KEYS[1], 'authVersion', ARGV[2], 'state', 'BLOCKED', 'pendingChangeId', ARGV[1])
return 'BLOCKED'
