-- 고정 시간창 요청 제한. 첫 요청이 시간창을 시작하며 이후 요청은 만료를 연장하지 않는다.
-- KEYS[1] = commerce-settle:auth:rate-limit:<operation>:<actorKind>:<identifierHash>
-- ARGV[1] = 시간창 안의 허용 횟수, ARGV[2] = 시간창(ms)
-- 0(허용) | 남은 시간창(ms, 제한)
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
local ttl = redis.call('PTTL', KEYS[1])
-- 만료 없이 남은 카운터는 영구 차단이 되므로 지금부터 한 시간창으로 고친다.
if ttl < 0 then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  ttl = tonumber(ARGV[2])
end
if count <= tonumber(ARGV[1]) then
  return 0
end
return ttl
