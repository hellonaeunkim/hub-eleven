# Product Load Test

재고 API의 성능 병목을 분석하고, 최적화 전후 결과를 동일한 조건에서 비교하기 위한 k6 부하 테스트입니다.

---

## 구성

| 경로                                        | 설명                                          |
|---------------------------------------------|-----------------------------------------------|
| `seed/seed-scenario1-lock.sql`              | 재고 차감 동시 요청 테스트용 초기 데이터      |
| `docs/scenario1-lock-design.md`             | 재고 차감 부하 테스트의 시나리오 및 설계 근거 |
| `docs/test-environment-isolation-design.md` | 테스트 데이터 저장소 분리 설계 및 선택 근거   |
| `scripts/scenario1-stock-decrease-lock.js`  | 재고 차감 부하 테스트 k6 스크립트             |
| `run-measurement.sh`                        | 웜업·스냅샷·비교·검증 자동 실행 스크립트      |
| `results/`                                  | k6 실행 결과 JSON 저장 위치                   |

---

## 환경 구성

부하 테스트는 개발 환경과 데이터가 섞이지 않도록 다음 저장 공간을 사용합니다.

| 용도               | MySQL 스키마         | Redis 논리 DB |
|--------------------|----------------------|---------------|
| 개발 환경          | `hubeleven`          | DB 0          |
| Spring Boot 테스트 | `hubeleven_test`     | DB 1          |
| k6 부하 테스트     | `hubeleven_loadtest` | DB 2          |

Homebrew MySQL은 중지하고 Docker Compose의 MySQL과 Redis를 실행합니다. 다음 명령은 프로젝트 루트에서 실행합니다.

```bash
brew services stop mysql
docker compose --env-file .env -f infra/docker-compose.yml up -d mysql redis
```

초기화 SQL은 새로운 MySQL 볼륨을 생성할 때만 자동 실행됩니다. 기존 볼륨에 테스트 스키마가 없다면 다음 명령으로 스키마를 생성합니다.

```bash
docker compose --env-file .env -f infra/docker-compose.yml exec -T mysql \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < infra/init/01_create_schemas.sql
```

기존 `hubEleven` 데이터를 사용해야 한다면 `hubeleven`으로 이전한 후 서비스를 실행합니다. 데이터 이전과 검증이 끝나기 전에는 기존 스키마를 삭제하지 않습니다.

eureka, config, product 서비스는 각각 별도 터미널에서 순서대로 기동합니다. product 서비스는 기본 `prod` 프로필과 `loadtest` 프로필을 함께 사용해야 합니다.

```bash
# 터미널 1
./gradlew :eurekaServer:bootRun

# 터미널 2
./gradlew :config:bootRun

# 터미널 3
DB_USERNAME="$(sed -n 's/^DB_USERNAME=//p' .env)" \
DB_PASSWORD="$(sed -n 's/^DB_PASSWORD=//p' .env)" \
SPRING_PROFILES_ACTIVE=prod,loadtest \
./gradlew :product:bootRun
```

`loadtest` 프로필은 product 서비스의 datasource를 `hubeleven_loadtest`, Redisson의 Redis 논리 DB를 DB 2로 변경합니다.

테스트가 끝나면 서비스를 종료한 후 사용하지 않는 컨테이너를 중지합니다.

```bash
docker compose --env-file .env -f infra/docker-compose.yml stop mysql redis
```

Redis 전체 DB를 삭제하는 `FLUSHALL`은 사용하지 않습니다.

---

## 시드 데이터 실행 방법

```bash
# 1. product 서비스 최소 1회 기동 (ddl-auto: update로 p_product/p_stock 테이블 생성)

# 2. 시드 데이터 삽입
mysql -h 127.0.0.1 -P 3306 -u root -p hubeleven_loadtest < seed/seed-scenario1-lock.sql
```

시드 SQL은 멱등이라 여러 번 실행해도 동일한 초기 상태로 리셋됩니다.

---

## k6 실행 방법

사전 조건: MySQL, Redis, eureka, config, product 서비스가 기동되어 있고 시드 데이터가 삽입된 상태여야 합니다.

결과 파일이 `results/` 에 저장되도록 `product/load-test` 디렉터리에서 실행합니다.

```bash
cd product/load-test

# 탐색 실행: 웜업 → VU 20 → 50 → 100 → 200 순차 실행 후 단계별 지표 확인
k6 run --summary-export results/explore.json \
  -e TEST_MODE=explore scripts/scenario1-stock-decrease-lock.js

# 웜업 실행: VU 10, 30초 (Micrometer 스냅샷 A 이전 단계)
k6 run -e TEST_MODE=warmup scripts/scenario1-stock-decrease-lock.js

# 비교 실행: 지정한 VU로 본 측정만 실행 (리팩토링 전/후 동일 명령으로 반복)
k6 run --summary-export results/compare-vus200.json \
  -e TEST_MODE=compare -e VUS=200 scripts/scenario1-stock-decrease-lock.js
```

결과는 실행 직후 터미널에 k6 기본 요약으로 출력되고, `--summary-export`를 지정하면 같은 내용이 JSON으로도 저장됩니다. 전후 비교와 자동화에는 반드시 JSON을 사용하고 터미널 출력은 사람이 확인하는 용도로만 씁니다.

| 환경변수      | 기본값                                 | 용도                           |
|---------------|----------------------------------------|--------------------------------|
| `TEST_MODE`   | `explore`                              | `explore`, `warmup`, `compare` |
| `VUS`         | 없음 (compare 모드 필수)               | 비교 실행의 동시 VU 수         |
| `DURATION`    | `1m`                                   | 비교 실행의 측정 시간          |
| `BASE_URL`    | `http://localhost:8085`                | product 서비스 주소            |
| `PRODUCT_ID`  | `10000000-0000-0000-0000-000000000001` | 시드 데이터의 테스트 상품      |
| `REQUEST_TIMEOUT` | `15s`                              | 개별 HTTP 요청 제한시간         |

결과 JSON 경로는 환경변수가 아니라 k6 옵션 `--summary-export <경로>`로 지정합니다.

`compare` 모드에는 웜업이 포함되지 않고 `startTime: '0s'`부터 본 측정만 수행합니다. Micrometer Timer는 애플리케이션 시작 이후 누적되므로, 본 측정 구간만 분리하려면 웜업을 별도
실행한 뒤 스냅샷 A를 저장하고 compare 실행 후 스냅샷 B를 저장해 차분해야 합니다. 웜업과 compare 사이에 서버를 재시작하지 않으므로 예열 효과는 유지됩니다.

리팩토링 전/후 측정 사이에는 시드 SQL을 다시 실행해 시작 조건을 동일하게 맞춥니다.

---

## 자동 측정 실행 방법

자동화 스크립트는 시드 초기화, warmup, 스냅샷 A, compare, 완충 대기, 스냅샷 B, 결과 검증을 순서대로 수행합니다. 측정 코드 버전을 명확히 남기기 위해 `results/`를 제외한 작업 트리가 깨끗해야 하므로, 관련 변경을 먼저 커밋하고 product 서비스를 재시작한 뒤 실행합니다.

```bash
cd product/load-test

# 리팩토링 전 1회차
PHASE=before RUN=1 ./run-measurement.sh

# 리팩토링 후 1회차
PHASE=after RUN=1 ./run-measurement.sh
```

| 환경변수 | 기본값 | 용도 |
|---|---:|---|
| `PHASE` | 없음 | `before` 또는 `after` |
| `RUN` | 없음 | 같은 조건의 실행 번호 |
| `VUS` | `200` | compare 동시 VU 수 |
| `DURATION_SECONDS` | `60` | compare 측정 시간(초) |
| `STABILIZE_SECONDS` | `5` | compare 종료 후 서버 처리 완충 시간(초) |
| `REQUEST_TIMEOUT` | `15s` | k6 개별 HTTP 요청 제한시간 |
| `CURL_MAX_TIME` | `10` | Actuator·재고 확인 요청 제한시간(초) |

종료 코드 `0`은 `VALID`, `1`은 실행 조건·파일·인프라·파싱 오류, `2`는 `ownership_lost` 발생, `3`은 교차 검증 또는 유효성 검증 실패를 의미합니다. 실패한 실행 결과도 덮어쓰지 않으므로 재측정할 때는 다음 `RUN` 번호를 사용합니다.

각 RUN은 측정 시도 자체를 의미하며, `VALID` 여부와 관계없이 결과를 보존합니다. 실패한 실행을 같은 RUN 번호로 덮어쓰면 오류 원인과 측정 이력이 사라지고, 여러 결과 파일이 서로 다른 실행 데이터로 섞일 수 있습니다. 따라서 재측정할 때는 기존 파일을 삭제하지 않고 다음 RUN 번호를 사용합니다.

---

## 측정 절차 (매 측정마다 동일하게)

측정 결과로 사용할 실행은 반드시 아래 순서를 지킵니다.

```text
1. product 서비스 재시작
2. 시드 SQL 실행
3. warmup 실행
4. Micrometer 스냅샷 A 저장
5. compare 실행
6. 서버 측 요청 처리를 위한 완충 대기
7. Micrometer 스냅샷 B 저장
```

warmup의 락 Timer는 HTTP 응답을 전송하기 전에 기록됩니다. warmup 중 요청 실패가 발생하면 k6가 실패로 종료되어 자동화가 중단되므로, 정상 종료된 warmup 뒤에는 별도의 완충 대기 없이 스냅샷 A를 수집합니다.

compare 종료 후에는 기본 5초의 완충 시간을 두고 스냅샷 B를 수집합니다. 고정 대기 시간 자체가 모든 서버 요청의 완료를 보장하지는 않으므로, `wait` 종료 결과 증가량 합계와 `http_reqs{scenario:compare}`를 교차 검증합니다. 두 값이 다르면 측정 결과를 `INVALID`로 처리합니다.

**서버를 재시작하는 이유**: 재시작 없이 연속 실행했을 때 동일 조건인데도 처리량이 크게 떨어졌고 Redisson 오류로 500 응답이 다수 발생하는 것을 관측했습니다. 원인을 특정하지는 않았지만 직전 실행의 영향이 남은 상태였으므로, 측정 시작 조건을 통일하기 위해 매 실행 전 서버를 재시작합니다.

**show-sql 을 끈 이유**: 요청마다 SQL 을 콘솔에 출력하는 비용 (문자열 정렬 + 출력 + 메모리 부담)이 측정에 잡음으로 섞입니다. 부하 테스트와 운영 환경 모두 끄는 것이 표준이며, 리팩토링
전/후에 동일하게 적용되므로 비교 유효성에는 영향이 없습니다.

**전/후 비교는 같은 세션에서 연달아 실행**: 동일한 절차라도 머신 상태 (재부팅 여부, 동시 실행 중인 프로세스)에 따라 측정 절대값이 날마다 달라지는 것을 실측으로 확인했습니다. 리팩토링 전/후의 비교 실행은
반드시 같은 날, 같은 세션에서 연달아 수행하고, 서로 다른 날의 측정값을 직접 비교하지 않습니다.
