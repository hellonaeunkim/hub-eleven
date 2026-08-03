import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const TEST_MODE = __ENV.TEST_MODE || 'explore';
const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8085').replace(/\/+$/, '');
const PRODUCT_ID = __ENV.PRODUCT_ID || '10000000-0000-0000-0000-000000000001';
const DECREASE_QUANTITY = 1;
const MIN_REQUIRED_QUANTITY = 10000000;
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '15s';
const LOCK_TIMEOUT_CODE = 'STOCK_LOCK_TIMEOUT';

validateEnvironment();

const decreaseSuccesses = new Counter('stock_decrease_successes');
const lockTimeouts = new Counter('stock_lock_timeouts');
const unexpectedFailures = new Counter('stock_unexpected_failures');
const lockTimeoutRate = new Rate('stock_lock_timeout_rate');
const unexpectedFailureRate = new Rate('stock_unexpected_failure_rate');

// 200(차감 성공)과 409(락 타임아웃)는 예상된 응답 → http_req_failed 는 예상 밖 실패만 집계
http.setResponseCallback(http.expectedStatuses(200, 409));

const warmupScenario = {
    executor: 'constant-vus',
    vus: 10,
    duration: '30s',
    startTime: '0s',
    gracefulStop: '20s',
};

const scenarios = createScenarios();
const measuredScenarioNames = Object.keys(scenarios).filter((name) => name !== 'warmup');

export const options = {
    scenarios,
    thresholds: createThresholds(measuredScenarioNames),
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    setupTimeout: '30s',
    teardownTimeout: '30s',
};

function createScenarios() {
    if (TEST_MODE === 'warmup') {
        return { warmup: warmupScenario };
    }

    if (TEST_MODE === 'compare') {
        return createCompareScenarios();
    }

    return createExploreScenarios();
}

function createExploreScenarios() {
    return {
        warmup: warmupScenario,
        vus_20: {
            executor: 'constant-vus',
            vus: 20,
            duration: '1m',
            startTime: '1m',
            gracefulStop: '20s',
        },
        vus_50: {
            executor: 'constant-vus',
            vus: 50,
            duration: '1m',
            startTime: '2m30s',
            gracefulStop: '20s',
        },
        vus_100: {
            executor: 'constant-vus',
            vus: 100,
            duration: '1m',
            startTime: '4m',
            gracefulStop: '20s',
        },
        vus_200: {
            executor: 'constant-vus',
            vus: 200,
            duration: '1m',
            startTime: '5m30s',
            gracefulStop: '20s',
        },
    };
}

function createCompareScenarios() {
    return {
        compare: {
            executor: 'constant-vus',
            vus: requiredPositiveInteger('VUS'),
            duration: __ENV.DURATION || '1m',
            startTime: '0s',
            gracefulStop: '20s',
        },
    };
}

function createThresholds(scenarioNames) {
    const thresholds = {
        http_req_failed: ['rate==0'],
        stock_unexpected_failures: ['count==0'],
    };

    for (const name of scenarioNames) {
        thresholds[`http_req_duration{scenario:${name}}`] = ['max>=0'];
        thresholds[`stock_decrease_successes{scenario:${name}}`] = ['count>=0'];
        thresholds[`stock_lock_timeouts{scenario:${name}}`] = ['count>=0'];
        thresholds[`stock_lock_timeout_rate{scenario:${name}}`] = ['rate>=0'];
        thresholds[`stock_unexpected_failures{scenario:${name}}`] = ['count>=0'];
    }

    return thresholds;
}

export function setup() {
    const res = getStock('setup_stock');
    const quantity = readStockQuantity(res, '초기');

    if (quantity < MIN_REQUIRED_QUANTITY) {
        throw new Error(`초기 재고 부족(${quantity}) - seed-scenario1-lock.sql 재실행이 필요합니다`);
    }

    console.log(`초기 재고: ${quantity}`);

    return { initialQuantity: quantity };
}

export default function () {
    const res = http.put(
        `${BASE_URL}/v1/stocks`,
        JSON.stringify({ productId: PRODUCT_ID, quantity: DECREASE_QUANTITY }),
        {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'PUT /v1/stocks', request_type: 'stock_decrease' },
            timeout: REQUEST_TIMEOUT,
        },
    );

    const body = parseResponseBody(res);

    const success =
        res.status === 200 &&
        body?.success === true &&
        body?.data?.productId === PRODUCT_ID &&
        Number.isFinite(Number(body?.data?.quantity));

    const lockTimeout = isLockTimeoutResponse(res, body);
    const unexpected = !success && !lockTimeout;

    if (success) {
        decreaseSuccesses.add(1);
    }

    if (lockTimeout) {
        lockTimeouts.add(1);
    }

    if (unexpected) {
        unexpectedFailures.add(1);
    }

    lockTimeoutRate.add(lockTimeout);
    unexpectedFailureRate.add(unexpected);

    check(res, {
        '차감 성공 또는 락 타임아웃': () => success || lockTimeout,
    });
}

export function teardown(data) {
    const res = getStock('teardown_stock');

    if (res.status !== 200) {
        console.error(`최종 재고 조회 실패(status: ${res.status})`);
        return;
    }

    const finalQuantity = Number(res.json('data.quantity'));

    if (!Number.isFinite(finalQuantity)) {
        console.error('최종 재고 응답에서 quantity를 확인할 수 없습니다');
        return;
    }

    const decreasedQuantity = data.initialQuantity - finalQuantity;

    console.log(`초기 재고: ${data.initialQuantity}`);
    console.log(`최종 재고: ${finalQuantity}`);
    console.log(`차감된 수량: ${decreasedQuantity}`);
    console.log('차감된 수량이 summary의 stock_decrease_successes count와 일치하는지 확인하세요');
}

function getStock(requestType) {
    return http.get(`${BASE_URL}/v1/stocks/${PRODUCT_ID}`, {
        tags: { name: 'GET /v1/stocks/{productId}', request_type: requestType },
        timeout: REQUEST_TIMEOUT,
    });
}

function readStockQuantity(res, phase) {
    if (res.status !== 200) {
        throw new Error(`${phase} 재고 조회 실패(status: ${res.status}) - product 서비스와 시드 데이터를 확인하세요`);
    }

    const quantity = Number(res.json('data.quantity'));

    if (!Number.isFinite(quantity)) {
        throw new Error(`${phase} 재고 응답에서 quantity를 확인할 수 없습니다`);
    }

    return quantity;
}

// 현재 에러 응답의 code 필드는 null (공통 라이브러리 ErrorResponse.of()가 code를 채우지 않음)
// code가 채워지기 시작하면 자동으로 코드 비교로 전환되는 전방 호환 판별
function isLockTimeoutResponse(res, body) {
    if (res.status !== 409 || body?.success !== false) {
        return false;
    }

    const code = body?.data?.code;

    if (code === null || code === undefined) {
        return true;
    }

    return code === LOCK_TIMEOUT_CODE;
}

function parseResponseBody(res) {
    try {
        return res.json();
    } catch (error) {
        return null;
    }
}

function validateEnvironment() {
    const supportedModes = ['explore', 'warmup', 'compare'];

    if (!supportedModes.includes(TEST_MODE)) {
        throw new Error('TEST_MODE는 explore, warmup 또는 compare여야 합니다.');
    }
}

function requiredPositiveInteger(name) {
    const value = __ENV[name];

    if (value === undefined || value === '') {
        throw new Error(`${name} 환경변수가 필요합니다.`);
    }

    const parsedValue = Number(value);

    if (!Number.isInteger(parsedValue) || parsedValue <= 0) {
        throw new Error(`${name}은 양의 정수여야 합니다. 입력값=${value}`);
    }

    return parsedValue;
}
