-- Hybrid token bucket + sliding window rate limiter.
-- KEYS[1] token bucket hash, KEYS[2] sliding window zset
-- ARGV: now_ms, capacity, refill_per_ms, requested_weight, window_ms, window_limit
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refill_per_ms = tonumber(ARGV[3])
local weight = tonumber(ARGV[4])
local window_ms = tonumber(ARGV[5])
local window_limit = tonumber(ARGV[6])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
local tokens = tonumber(bucket[1]) or capacity
local updated = tonumber(bucket[2]) or now
tokens = math.min(capacity, tokens + ((now - updated) * refill_per_ms))

redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, now - window_ms)
local window_count = redis.call('ZCARD', KEYS[2])

if tokens >= weight and (window_count + weight) <= window_limit then
  tokens = tokens - weight
  redis.call('HMSET', KEYS[1], 'tokens', tokens, 'updated', now)
  redis.call('PEXPIRE', KEYS[1], window_ms)
  for i = 1, weight do
    redis.call('ZADD', KEYS[2], now, now .. ':' .. i .. ':' .. redis.call('INCR', KEYS[2] .. ':seq'))
  end
  redis.call('PEXPIRE', KEYS[2], window_ms)
  return {1, 0}
end

local token_wait = 0
if tokens < weight then
  token_wait = math.ceil((weight - tokens) / refill_per_ms)
end

local oldest = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
local window_wait = 0
if oldest[2] ~= nil then
  window_wait = math.max(0, window_ms - (now - tonumber(oldest[2])))
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'updated', now)
return {0, math.ceil(math.max(token_wait, window_wait) / 1000)}
