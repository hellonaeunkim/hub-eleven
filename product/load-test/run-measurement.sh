#!/usr/bin/env bash
set -euo pipefail

PHASE=${PHASE:-}
RUN=${RUN:-}
VUS=${VUS:-200}
DURATION_SECONDS=${DURATION_SECONDS:-60}
STABILIZE_SECONDS=${STABILIZE_SECONDS:-5}
REQUEST_TIMEOUT=${REQUEST_TIMEOUT:-15s}
CURL_MAX_TIME=${CURL_MAX_TIME:-10}
BASE_URL=${BASE_URL:-http://localhost:8085}
PRODUCT_ID=${PRODUCT_ID:-10000000-0000-0000-0000-000000000001}

BASE_URL=${BASE_URL%/}

abort() {
    echo "[중단] $*" >&2
    exit 1
}

step() {
    echo
    echo "== $* =="
}

[[ "$PHASE" == "before" || "$PHASE" == "after" ]] \
    || abort "PHASE는 before 또는 after여야 합니다."

[[ "$RUN" =~ ^[1-9][0-9]*$ ]] \
    || abort "RUN은 1 이상의 정수여야 합니다."

[[ "$VUS" =~ ^[1-9][0-9]*$ ]] \
    || abort "VUS는 1 이상의 정수여야 합니다."

[[ "$DURATION_SECONDS" =~ ^[1-9][0-9]*$ ]] \
    || abort "DURATION_SECONDS는 1 이상의 정수여야 합니다."

[[ "$STABILIZE_SECONDS" =~ ^[0-9]+$ ]] \
    || abort "STABILIZE_SECONDS는 0 이상의 정수여야 합니다."

[[ "$REQUEST_TIMEOUT" =~ ^[1-9][0-9]*(ms|s|m|h)$ ]] \
    || abort "REQUEST_TIMEOUT은 15s, 1m처럼 양의 정수와 시간 단위를 사용해야 합니다."

[[ "$CURL_MAX_TIME" =~ ^[1-9][0-9]*$ ]] \
    || abort "CURL_MAX_TIME은 1 이상의 정수여야 합니다."

for command in curl docker git grep jq k6; do
    command -v "$command" >/dev/null \
        || abort "$command 명령을 찾을 수 없습니다."
done

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LOAD_TEST_DIR=$(cd "$SCRIPT_DIR" && pwd)
REPO_ROOT=$(git -C "$LOAD_TEST_DIR" rev-parse --show-toplevel)

K6_SCRIPT="$LOAD_TEST_DIR/scripts/scenario1-stock-decrease-lock.js"
SEED_FILE="$LOAD_TEST_DIR/seed/seed-scenario1-lock.sql"
RESULTS_DIR="$LOAD_TEST_DIR/results/scenario1-lock"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE_FILE="$REPO_ROOT/infra/docker-compose.yml"
LOCK_MANAGER="$REPO_ROOT/product/src/main/java/com/hubEleven/stock/infrastructure/lock/RedissonStockLockManager.java"

RUN_DIR="$RESULTS_DIR/$PHASE/run$RUN"

META_FILE="$RUN_DIR/meta.json"
WARMUP_LOG="$RUN_DIR/warmup.log"
COMPARE_LOG="$RUN_DIR/compare.log"
K6_RESULT="$RUN_DIR/k6.json"
SNAPSHOT_A="$RUN_DIR/metrics-a.json"
SNAPSHOT_B="$RUN_DIR/metrics-b.json"
VERDICT_FILE="$RUN_DIR/verdict.json"

mkdir -p "$RESULTS_DIR"

for file in \
    "$META_FILE" \
    "$WARMUP_LOG" \
    "$COMPARE_LOG" \
    "$K6_RESULT" \
    "$SNAPSHOT_A" \
    "$SNAPSHOT_B" \
    "$VERDICT_FILE"; do

    [[ ! -e "$file" ]] \
        || abort "결과 파일이 이미 존재합니다: $file
기존 측정 결과는 덮어쓰지 않습니다. RUN에 아직 사용하지 않은 다음 실행 번호를 지정하세요."
done

mkdir -p "$RUN_DIR"

DIRTY=$(
    git -C "$REPO_ROOT" status --porcelain -- \
        . ':(exclude)product/load-test/results/**'
)

[[ -z "$DIRTY" ]] \
    || abort $'커밋되지 않은 소스 변경이 있습니다.\n'"$DIRTY"

COMMIT_SHA=$(git -C "$REPO_ROOT" rev-parse HEAD)
BRANCH=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
K6_VERSION=$(k6 version)

timer_metric() {
    local metric=$1
    local result=$2
    local response

    response=$(
        curl -fsS --max-time "$CURL_MAX_TIME" \
            -G "$BASE_URL/actuator/metrics/$metric" \
            --data-urlencode "tag=result:$result"
    ) || abort "지표 조회 실패: $metric{result=$result}"

    jq -e --arg metric "$metric" --arg result "$result" '
        def measurement($name):
            [.measurements[]
             | select(.statistic == $name)
             | .value][0];

        if .baseUnit != "seconds" then
            error(
                "\($metric){result=\($result)}의 baseUnit이 seconds가 아닙니다."
            )
        elif measurement("COUNT") == null
          or measurement("TOTAL_TIME") == null
          or measurement("MAX") == null then
            error(
                "\($metric){result=\($result)}의 측정값이 누락됐습니다."
            )
        else
            {
                count: measurement("COUNT"),
                totalTime: measurement("TOTAL_TIME"),
                max: measurement("MAX")
            }
        end
    ' <<< "$response" \
        || abort "지표 파싱 실패: $metric{result=$result}"
}

process_start_time() {
    local response

    response=$(
        curl -fsS --max-time "$CURL_MAX_TIME" \
            "$BASE_URL/actuator/metrics/process.start.time"
    ) || abort "process.start.time 조회에 실패했습니다."

    jq -e '
        [.measurements[]
         | select(.statistic == "VALUE")
         | .value][0]
        | if . == null then
            error("process.start.time 값이 없습니다.")
          else
            .
          end
    ' <<< "$response" \
        || abort "process.start.time 파싱에 실패했습니다."
}

stock_quantity() {
    local response

    response=$(
        curl -fsS --max-time "$CURL_MAX_TIME" \
            "$BASE_URL/v1/stocks/$PRODUCT_ID"
    ) || abort "재고 조회에 실패했습니다."

    jq -e '
        .data.quantity
        | if type != "number" then
            error("재고 수량을 확인할 수 없습니다.")
          else
            .
          end
    ' <<< "$response" \
        || abort "재고 수량 파싱에 실패했습니다."
}

capture_snapshot() {
    local output=$1
    local temporary="${output}.tmp"

    local wait_acquired
    local wait_timeout
    local wait_interrupted
    local wait_error
    local hold_success
    local hold_error
    local hold_ownership_lost
    local started_at
    local quantity

    wait_acquired=$(timer_metric stock.lock.wait acquired)
    wait_timeout=$(timer_metric stock.lock.wait timeout)
    wait_interrupted=$(timer_metric stock.lock.wait interrupted)
    wait_error=$(timer_metric stock.lock.wait error)

    hold_success=$(timer_metric stock.lock.hold success)
    hold_error=$(timer_metric stock.lock.hold error)
    hold_ownership_lost=$(
        timer_metric stock.lock.hold ownership_lost
    )

    started_at=$(process_start_time)
    quantity=$(stock_quantity)

    if ! jq -n \
        --argjson waitAcquired "$wait_acquired" \
        --argjson waitTimeout "$wait_timeout" \
        --argjson waitInterrupted "$wait_interrupted" \
        --argjson waitError "$wait_error" \
        --argjson holdSuccess "$hold_success" \
        --argjson holdError "$hold_error" \
        --argjson holdOwnershipLost "$hold_ownership_lost" \
        --argjson processStartTime "$started_at" \
        --argjson stockQuantity "$quantity" \
        '{
            capturedAt: (now | todate),
            processStartTime: $processStartTime,
            stockQuantity: $stockQuantity,
            wait: {
                acquired: $waitAcquired,
                timeout: $waitTimeout,
                interrupted: $waitInterrupted,
                error: $waitError
            },
            hold: {
                success: $holdSuccess,
                error: $holdError,
                ownership_lost: $holdOwnershipLost
            }
        }' > "$temporary"; then

        rm -f "$temporary"
        abort "스냅샷 생성에 실패했습니다: $output"
    fi

    mv "$temporary" "$output"
}

assert_timer_counts_are_zero() {
    local metric
    local result
    local timer
    local count

    while read -r metric result; do
        timer=$(timer_metric "$metric" "$result")
        count=$(jq -r '.count' <<< "$timer")

        if ! jq -e '.count == 0' <<< "$timer" >/dev/null; then
            abort \
                "Timer 누적값이 남아 있습니다: $metric{result=$result}=$count. Product 서비스를 재시작하세요."
        fi
    done <<'EOF'
stock.lock.wait acquired
stock.lock.wait timeout
stock.lock.wait interrupted
stock.lock.wait error
stock.lock.hold success
stock.lock.hold error
stock.lock.hold ownership_lost
EOF
}

lock_constant() {
    local name=$1
    local matched
    local value

    matched=$(
        grep -m1 -oE \
            "${name}[[:space:]]*=[[:space:]]*[0-9]+" \
            "$LOCK_MANAGER"
    ) || abort "락 상수를 찾을 수 없습니다: $name"

    value=$(
        grep -oE '[0-9]+$' <<< "$matched"
    ) || abort "락 상수 값을 추출할 수 없습니다: $name"

    [[ -n "$value" ]] \
        || abort "락 상수 값이 비어 있습니다: $name"

    echo "$value"
}

step "1. 실행 조건 확인"

echo "PHASE=$PHASE"
echo "RUN=$RUN"
echo "VUS=$VUS"
echo "DURATION=${DURATION_SECONDS}s"
echo "STABILIZE=${STABILIZE_SECONDS}s"
echo "REQUEST_TIMEOUT=$REQUEST_TIMEOUT"
echo "CURL_MAX_TIME=${CURL_MAX_TIME}s"
echo "COMMIT=$COMMIT_SHA"
echo "BRANCH=$BRANCH"
echo "K6=$K6_VERSION"

[[ -f "$K6_SCRIPT" ]] \
    || abort "k6 스크립트를 찾을 수 없습니다: $K6_SCRIPT"

[[ -f "$SEED_FILE" ]] \
    || abort "시드 SQL을 찾을 수 없습니다: $SEED_FILE"

[[ -f "$ENV_FILE" ]] \
    || abort "환경변수 파일을 찾을 수 없습니다: $ENV_FILE"

[[ -f "$COMPOSE_FILE" ]] \
    || abort "Docker Compose 파일을 찾을 수 없습니다: $COMPOSE_FILE"

[[ -f "$LOCK_MANAGER" ]] \
    || abort "락 매니저 소스를 찾을 수 없습니다: $LOCK_MANAGER"

docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    exec -T mysql \
    sh -c '
        mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
            USE hubeleven_loadtest;
            SELECT 1 FROM p_product LIMIT 0;
            SELECT 1 FROM p_stock LIMIT 0;
        "
    ' >/dev/null 2>&1 \
    || abort \
        "부하 테스트 DB에 접근할 수 없습니다. MySQL 컨테이너, 인증 정보, hubeleven_loadtest 스키마와 테이블을 확인하세요."

REDIS_PING=$(
    docker compose \
        --env-file "$ENV_FILE" \
        -f "$COMPOSE_FILE" \
        exec -T redis redis-cli --raw ping 2>/dev/null
) || abort "Redis 컨테이너에 접근할 수 없습니다. docker compose로 redis를 먼저 기동하세요."

[[ "$REDIS_PING" == "PONG" ]] \
    || abort "Redis PING 응답이 올바르지 않습니다: $REDIS_PING"

curl -fsS --max-time "$CURL_MAX_TIME" \
    "$BASE_URL/actuator/health" >/dev/null \
    || abort "Product 서비스가 실행 중인지 확인하세요."

curl -fsS --max-time "$CURL_MAX_TIME" \
    "$BASE_URL/actuator/metrics/stock.lock.wait" \
    >/dev/null \
    || abort \
        "stock.lock.wait 지표가 없습니다. Timer 계측과 SPRING_PROFILES_ACTIVE=prod,loadtest 설정을 확인하세요."

assert_timer_counts_are_zero

step "2. 메타데이터 저장"

WAIT_TIME_SECONDS=$(lock_constant WAIT_TIME_SECONDS)
LEASE_TIME_SECONDS=$(lock_constant LEASE_TIME_SECONDS)
MAX_RETRY_COUNT=$(lock_constant MAX_RETRY_COUNT)
RETRY_BACKOFF_MILLIS=$(
    lock_constant RETRY_BACKOFF_MILLIS
)

META_TMP="${META_FILE}.tmp"

if ! jq -n \
    --arg phase "$PHASE" \
    --argjson run "$RUN" \
    --argjson vus "$VUS" \
    --argjson durationSeconds "$DURATION_SECONDS" \
    --argjson stabilizeSeconds "$STABILIZE_SECONDS" \
    --arg requestTimeout "$REQUEST_TIMEOUT" \
    --argjson curlMaxTimeSeconds "$CURL_MAX_TIME" \
    --arg commitSha "$COMMIT_SHA" \
    --arg branch "$BRANCH" \
    --arg measuredAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg baseUrl "$BASE_URL" \
    --arg productId "$PRODUCT_ID" \
    --arg k6Version "$K6_VERSION" \
    --argjson waitTimeSeconds "$WAIT_TIME_SECONDS" \
    --argjson leaseTimeSeconds "$LEASE_TIME_SECONDS" \
    --argjson maxRetryCount "$MAX_RETRY_COUNT" \
    --argjson retryBackoffMillis "$RETRY_BACKOFF_MILLIS" \
    '{
        phase: $phase,
        run: $run,
        vus: $vus,
        durationSeconds: $durationSeconds,
        stabilizeSeconds: $stabilizeSeconds,
        requestTimeout: $requestTimeout,
        curlMaxTimeSeconds: $curlMaxTimeSeconds,
        commitSha: $commitSha,
        branch: $branch,
        measuredAt: $measuredAt,
        baseUrl: $baseUrl,
        productId: $productId,
        k6Version: $k6Version,
        lockPolicy: {
            waitTimeSeconds: $waitTimeSeconds,
            leaseTimeSeconds: $leaseTimeSeconds,
            maxRetryCount: $maxRetryCount,
            retryBackoffMillis: $retryBackoffMillis
        }
    }' > "$META_TMP"; then

    rm -f "$META_TMP"
    abort "메타데이터 생성에 실패했습니다."
fi

mv "$META_TMP" "$META_FILE"
echo "저장: $META_FILE"

step "3. 시드 데이터 초기화"

docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    exec -T mysql \
    sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' \
    < "$SEED_FILE" \
    || abort "시드 데이터 초기화에 실패했습니다."

echo "시드 초기화 완료: hubeleven_loadtest"

step "4. warmup 실행"

(
    cd "$LOAD_TEST_DIR"

    k6 run \
        -e TEST_MODE=warmup \
        -e BASE_URL="$BASE_URL" \
        -e PRODUCT_ID="$PRODUCT_ID" \
        -e REQUEST_TIMEOUT="$REQUEST_TIMEOUT" \
        "$K6_SCRIPT"
) > "$WARMUP_LOG" 2>&1 \
    || abort "warmup 실행에 실패했습니다: $WARMUP_LOG"

echo "warmup 로그: $WARMUP_LOG"

step "5. 스냅샷 A 저장"

capture_snapshot "$SNAPSHOT_A"
echo "저장: $SNAPSHOT_A"

step "6. compare 실행"

set +e

(
    cd "$LOAD_TEST_DIR"

    k6 run \
        --summary-export "$K6_RESULT" \
        -e TEST_MODE=compare \
        -e VUS="$VUS" \
        -e DURATION="${DURATION_SECONDS}s" \
        -e BASE_URL="$BASE_URL" \
        -e PRODUCT_ID="$PRODUCT_ID" \
        -e REQUEST_TIMEOUT="$REQUEST_TIMEOUT" \
        "$K6_SCRIPT"
) 2>&1 | tee "$COMPARE_LOG"

K6_EXIT=${PIPESTATUS[0]}

set -e

echo "compare 로그: $COMPARE_LOG"

if [[ "$K6_EXIT" -ne 0 ]]; then
    echo \
        "경고: k6가 비정상 종료되었거나 threshold를 위반했습니다. exit=$K6_EXIT"
    echo "스냅샷과 결과는 계속 수집합니다."
fi

[[ -f "$K6_RESULT" ]] \
    || abort \
        "k6 결과 파일이 생성되지 않았습니다: $K6_RESULT
compare 로그를 확인하세요: $COMPARE_LOG"

step "7. 서버 처리 완충 대기 (${STABILIZE_SECONDS}초)"

sleep "$STABILIZE_SECONDS"

step "8. 스냅샷 B 저장"

capture_snapshot "$SNAPSHOT_B"
echo "저장: $SNAPSHOT_B"

step "9. 결과 계산 및 교차 검증"

VERDICT_TMP="${VERDICT_FILE}.tmp"

if ! jq -e -n \
    --slurpfile metadata "$META_FILE" \
    --slurpfile a "$SNAPSHOT_A" \
    --slurpfile b "$SNAPSHOT_B" \
    --slurpfile k6 "$K6_RESULT" \
    --argjson durationSeconds "$DURATION_SECONDS" \
    --argjson k6Exit "$K6_EXIT" \
    '
    def required($value; $message):
        if $value == null then
            error($message)
        else
            $value
        end;

    ($metadata[0]) as $META
    | ($a[0]) as $A
    | ($b[0]) as $B
    | ($k6[0].metrics) as $M
    | ($M["http_reqs{scenario:compare}"]) as $requests
    | ($M["http_req_duration{scenario:compare}"]) as $duration
    | ($M["stock_decrease_successes{scenario:compare}"])
        as $successes
    | ($M["stock_lock_timeouts{scenario:compare}"])
        as $timeouts
    | ($M["stock_unexpected_failures{scenario:compare}"])
        as $unexpected
    | {
        metadata:
            ($META + {
                k6ExitCode: $k6Exit
            }),
        delta: {
            waitAcquired:
                ($B.wait.acquired.count
                - $A.wait.acquired.count),
            waitTimeout:
                ($B.wait.timeout.count
                - $A.wait.timeout.count),
            waitInterrupted:
                ($B.wait.interrupted.count
                - $A.wait.interrupted.count),
            waitError:
                ($B.wait.error.count
                - $A.wait.error.count),
            holdSuccess:
                ($B.hold.success.count
                - $A.hold.success.count),
            holdError:
                ($B.hold.error.count
                - $A.hold.error.count),
            holdOwnershipLost:
                ($B.hold.ownership_lost.count
                - $A.hold.ownership_lost.count)
        },
        k6: {
            requests:
                required(
                    $requests.count;
                    "compare 요청 수가 없습니다."
                ),
            successes:
                required(
                    $successes.count;
                    "성공 건수가 없습니다."
                ),
            timeouts:
                required(
                    $timeouts.count;
                    "타임아웃 건수가 없습니다."
                ),
            unexpected:
                required(
                    $unexpected.count;
                    "예상 밖 실패 건수가 없습니다."
                ),
            apiAvgMs:
                required(
                    $duration.avg;
                    "API 평균 응답시간이 없습니다."
                ),
            apiP95Ms:
                required(
                    $duration["p(95)"];
                    "API p95가 없습니다."
                ),
            apiP99Ms:
                required(
                    $duration["p(99)"];
                    "API p99가 없습니다."
                )
        },
        stock: {
            before: $A.stockQuantity,
            after: $B.stockQuantity,
            decreased:
                ($A.stockQuantity - $B.stockQuantity)
        },
        process: {
            startTimeA: $A.processStartTime,
            startTimeB: $B.processStartTime
        }
    }
    | .delta.waitTerminal = (
        .delta.waitAcquired
        + .delta.waitTimeout
        + .delta.waitInterrupted
        + .delta.waitError
      )
    | .delta.holdTotal = (
        .delta.holdSuccess
        + .delta.holdError
        + .delta.holdOwnershipLost
      )
    | .average = {
        lockWaitMs:
            (if .delta.waitAcquired > 0 then
                (
                    ($B.wait.acquired.totalTime
                    - $A.wait.acquired.totalTime)
                    / .delta.waitAcquired
                ) * 1000
            else
                null
            end),
        lockHoldMs:
            (if .delta.holdSuccess > 0 then
                (
                    ($B.hold.success.totalTime
                    - $A.hold.success.totalTime)
                    / .delta.holdSuccess
                ) * 1000
            else
                null
            end)
      }
    | .k6.successTps = (
        .k6.successes / $durationSeconds
      )
    | .reference = {
        waitMaxMs: ($B.wait.acquired.max * 1000),
        holdMaxMs: ($B.hold.success.max * 1000)
      }
    | .crossCheck = {
        allRequestsReachedTerminalWait:
            (.delta.waitTerminal == .k6.requests),
        acquiredEqualsHoldTotal:
            (.delta.waitAcquired == .delta.holdTotal),
        timeoutMatches:
            (.delta.waitTimeout == .k6.timeouts),
        stockMatchesSuccess:
            (.stock.decreased == .k6.successes)
      }
    | .diagnostic = {
        holdSuccessMatchesK6Success:
            (.delta.holdSuccess == .k6.successes)
      }
    | .validity = {
        k6ExitZero:
            ($k6Exit == 0),
        sameProcess:
            (.process.startTimeA
            == .process.startTimeB),
        hasMeasurement:
            (.delta.waitAcquired > 0),
        nonNegativeDeltas:
            ([
                .delta.waitAcquired,
                .delta.waitTimeout,
                .delta.waitInterrupted,
                .delta.waitError,
                .delta.holdSuccess,
                .delta.holdError,
                .delta.holdOwnershipLost
             ] | all(. >= 0)),
        waitErrorZero:
            (.delta.waitError == 0),
        waitInterruptedZero:
            (.delta.waitInterrupted == 0),
        holdErrorZero:
            (.delta.holdError == 0),
        ownershipLostZero:
            (.delta.holdOwnershipLost == 0),
        unexpectedZero:
            (.k6.unexpected == 0)
      }
    | .verdict = (
        if ([.crossCheck[], .validity[]] | all) then
            "VALID"
        else
            "INVALID"
        end
      )
    ' > "$VERDICT_TMP"; then

    rm -f "$VERDICT_TMP"
    abort \
        "결과 계산에 실패했습니다. k6 sub-metric과 스냅샷을 확인하세요."
fi

mv "$VERDICT_TMP" "$VERDICT_FILE"
cat "$VERDICT_FILE"

echo

jq -r '
    def value_or_na($value; $suffix):
        if $value == null then
            "N/A"
        else
            "\($value | floor)\($suffix)"
        end;

    "판정: \(.verdict)"
    + " | 락대기 \(value_or_na(.average.lockWaitMs; "ms"))"
    + " | 락점유 \(value_or_na(.average.lockHoldMs; "ms"))"
    + " | 성공TPS \(value_or_na(.k6.successTps; ""))"
    + " | p95 \(value_or_na(.k6.apiP95Ms; "ms"))",

    ([
        (.crossCheck
         | to_entries[]
         | select(.value == false)
         | "crossCheck.\(.key)"),
        (.validity
         | to_entries[]
         | select(.value == false)
         | "validity.\(.key)")
    ]
    | if length == 0 then
        empty
      else
        "실패 항목: " + join(", ")
      end)
' "$VERDICT_FILE"

OWNERSHIP_LOST=$(
    jq -r '.delta.holdOwnershipLost' "$VERDICT_FILE"
)

VERDICT=$(
    jq -r '.verdict' "$VERDICT_FILE"
)

echo

if [[ "$OWNERSHIP_LOST" != "0" ]]; then
    echo \
        "ownership_lost ${OWNERSHIP_LOST}건이 발생했습니다."
    echo \
        "baseline 수집을 중단하고 lease 정책을 분석하세요."
    exit 2
fi

if [[ "$VERDICT" != "VALID" ]]; then
    echo "측정 결과가 INVALID입니다."
    echo \
        "같은 불일치가 반복되면 서버 측 요청 적체를 확인하세요."
    exit 3
fi

echo "측정 완료: $VERDICT_FILE"
