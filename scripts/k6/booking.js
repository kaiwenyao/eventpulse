# k6 负载 + 竞争脚本（库容正确性在负载下验证）

# 用法：docker run --rm -i grafana/k6 run - < scripts/k6/booking.js
# 或本地：k6 run scripts/k6/booking.js
# 断言：200/201 比例、p95；并发高峰下服务端保持不变量（库存等式由集成测试
# 与 after-response 校验响应语义，超卖表现为成功数 > 容量，可在聚合中观察）。

import http from 'k6/http'
import { check, sleep } from 'k6'
import exec from 'k6/execution'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const EVENT_ID = __ENV.EVENT_ID || ''
const TIER_ID = __ENV.TIER_ID || ''

export const options = {
  scenarios: {
    burst_booking: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { target: 20, duration: '30s' },
        { target: 50, duration: '30s' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.5'], // 409 库存不足属于预期拒绝
    'http_req_duration{expected_response:true}': ['p(95)<500'],
  },
}

function tokenB64() {
  const bytes = new Uint8Array(32)
  for (let i = 0; i < 32; i++) bytes[i] = Math.floor(Math.random() * 256)
  let bin = ''
  bytes.forEach((b) => (bin += String.fromCharCode(b)))
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export default function () {
  // 1) register + login a fresh user per iteration
  const email = `k6-${exec.scenario.iterationInTest}-${__VU}@k6.test`
  const register = http.post(`${BASE}/api/v1/auth/register`,
    JSON.stringify({ email, password: 'K6!234567890', displayName: 'k6' }),
    { headers: { 'Content-Type': 'application/json' } })
  check(register, { 'register 201': (r) => r.status === 201 })
  const token = register.json('accessToken')
  if (!token) return

  // 2) create a booking with a unique high-entropy idempotency key
  const key = tokenB64()
  const body = JSON.stringify({
    eventId: EVENT_ID, tierId: TIER_ID, quantity: 1, ageConfirmed: true,
  })
  const res = http.post(`${BASE}/api/v1/bookings`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': key },
  })
  check(res, {
    'booking 201 or 409': (r) => r.status === 201 || r.status === 409,
  })

  // 3) replay with the same key must return the same booking
  if (res.status === 201) {
    const replay = http.post(`${BASE}/api/v1/bookings`, body, {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': key },
    })
    check(replay, { 'replay same booking': (r) => r.json('id') === res.json('id') })
    const bookingId = res.json('id')
    http.post(`${BASE}/api/v1/bookings/${bookingId}/pay`, '{}', {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': tokenB64() },
    })
  }
  sleep(0.2)
}
