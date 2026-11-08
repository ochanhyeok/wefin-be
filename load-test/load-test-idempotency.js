/**
 * WeFin 멱등성 부하테스트
 *
 * 시나리오 A — 따닥(동시 중복): 10 VU가 같은 Idempotency-Key로 동시 요청
 *   기대: 1건 성공, 9건 Redis SETNX에서 즉시 차단
 *
 * 시나리오 B — 정상 병렬: 10 VU가 서로 다른 Key로 동시 요청
 *   기대: 잔고 한도 내 최대 성공
 *
 * 실행 방법:
 *   export EMAIL=your@email.com PASSWORD=yourPassword STOCK_CODE=005930
 *   k6 run load-test-idempotency.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL    = __ENV.EMAIL    || 'loadtest@example.com';
const PASSWORD = __ENV.PASSWORD || 'Test1234!';
const STOCK    = __ENV.STOCK_CODE || '005930';

// 커스텀 메트릭
const successCount  = new Counter('order_success');
const rejectedCount = new Counter('order_rejected');
const rejectionTime = new Trend('rejection_latency_ms', true);

export const options = {
    scenarios: {
        // 시나리오 A: 같은 키로 동시 10건 (따닥)
        duplicate_burst: {
            executor: 'shared-iterations',
            vus: 10,
            iterations: 10,
            maxDuration: '30s',
            tags: { scenario: 'A_duplicate' },
        },
        // 시나리오 B: 다른 키로 동시 10건 (정상)
        normal_burst: {
            executor: 'shared-iterations',
            vus: 10,
            iterations: 10,
            startTime: '35s',
            maxDuration: '30s',
            tags: { scenario: 'B_normal' },
        },
    },
    thresholds: {
        'order_rejected': ['count > 0'],
    },
};

// setup: 로그인 → JWT 반환
export function setup() {
    const res = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ email: EMAIL, password: PASSWORD }),
        { headers: { 'Content-Type': 'application/json' } }
    );
    check(res, { 'login 200': (r) => r.status === 200 });
    const token = res.json('data.accessToken');
    if (!token) {
        throw new Error(`로그인 실패: ${res.body}`);
    }
    return { token };
}

export default function (data) {
    const scenario  = exec.scenario.name;
    const isSameKey = scenario === 'duplicate_burst';

    // A: 전 VU가 같은 키 공유 / B: VU마다 새 키
    const idempotencyKey = isSameKey ? 'LOAD-TEST-SAME-KEY-001' : uuidv4();

    const payload = JSON.stringify({ stockCode: STOCK, quantity: 1 });
    const params  = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${data.token}`,
            'Idempotency-Key': idempotencyKey,
        },
        tags: { scenario },
    };

    const start = Date.now();
    const res   = http.post(`${BASE_URL}/api/order/buy`, payload, params);
    const elapsed = Date.now() - start;

    console.log(`[${scenario}] status=${res.status} body=${res.body.substring(0, 150)}`);

    const ok = res.status === 200;
    const rejected = res.status === 409
        || (res.json('code') === 'ORDER_DUPLICATE_REQUEST');

    check(res, {
        'success or rejected': () => ok || rejected,
    });

    if (ok)       { successCount.add(1); }
    if (rejected) { rejectedCount.add(1); rejectionTime.add(elapsed); }

    sleep(0.1);
}

export function handleSummary(data) {
    const aSuccess  = (data.metrics['order_success']  || {}).values?.count || 0;
    const aRejected = (data.metrics['order_rejected'] || {}).values?.count || 0;
    const p95ms     = (data.metrics['rejection_latency_ms'] || {}).values?.['p(95)'] || 0;

    console.log('\n========== 멱등성 부하테스트 결과 ==========');
    console.log(`시나리오 A (따닥): 성공 ${aSuccess}건 / Redis 차단 ${aRejected}건`);
    console.log(`차단 응답 p95: ${p95ms.toFixed(1)} ms`);
    console.log('==============================================\n');

    return { stdout: '' };
}
