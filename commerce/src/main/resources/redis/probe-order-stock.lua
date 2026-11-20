local remaining = redis.call('GET', KEYS[1])
if not remaining or not string.match(remaining, '^%d+$') then
    return 0
end

local quantity = tonumber(ARGV[1])
if tonumber(remaining) < quantity then
    return 1
end

-- 남은 시험용 수량만 차감한다. DB의 재고·예약·복원과 TTL은 독립적이다.
redis.call('DECRBY', KEYS[1], quantity)
return 0
