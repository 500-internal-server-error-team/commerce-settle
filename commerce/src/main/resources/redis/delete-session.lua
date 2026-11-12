-- Gateway가 전달한 신원의 세션일 때만 현재 세션을 삭제한다.
-- KEYS[1] = commerce-settle:auth:session:<epoch>:<tokenHash>, ARGV[1] = actorKind, ARGV[2] = actorId
-- DELETED(이미 없음 포함) | REJECTED(다른 계정의 세션, 401)
local session = redis.call('HMGET', KEYS[1], 'actorKind', 'actorId')
if not (session[1] or session[2]) then
  return 'DELETED'
end
if session[1] ~= ARGV[1] or session[2] ~= ARGV[2] then
  return 'REJECTED'
end
redis.call('DEL', KEYS[1])
return 'DELETED'
