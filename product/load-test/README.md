# Product Load Test

재고 API의 성능 병목을 분석하고, 최적화 전후 결과를 동일한 조건에서 비교하기 위한 k6 부하 테스트입니다.

---

## 구성

| 경로                                       | 설명                                          |
|--------------------------------------------|-----------------------------------------------|
| `seed/seed-scenario1-lock.sql`             | 재고 차감 동시 요청 테스트용 초기 데이터      |
| `docs/scenario1-lock-design.md`            | 재고 차감 부하 테스트의 시나리오 및 설계 근거 |
| `scripts/scenario1-stock-decrease-lock.js` | 재고 차감 부하 테스트 k6 스크립트             |
| `results/`                                 | k6 실행 결과 JSON 저장 위치                   |

---

## 시드 데이터 실행 방법

```bash
# 1. product 서비스 최소 1회 기동 (ddl-auto: update로 p_product/p_stock 테이블 생성)

# 2. 시드 데이터 삽입
mysql -h 127.0.0.1 -P 3306 -u root -p hubEleven < seed/seed-scenario1-lock.sql
```

시드 SQL은 멱등이라 여러 번 실행해도 동일한 초기 상태로 리셋됩니다.

---

## k6 실행 방법

사전 조건: MySQL, Redis, eureka, config, product 서비스가 기동되어 있고 시드 데이터가 삽입된 상태여야 합니다.

결과 파일이 `results/` 에 저장되도록 `product/load-test` 디렉터리에서 실행합니다.

```bash
cd product/load-test

# 탐색 실행: 웜업 → VU 20 → 50 → 100 → 200 순차 실행 후 단계별 지표 확인
k6 run -e TEST_MODE=explore scripts/scenario1-stock-decrease-lock.js

# 비교 실행: 탐색에서 정한 VU 지점을 지정해 실행 (리팩토링 전/후 동일 명령으로 반복)
k6 run -e TEST_MODE=compare -e VUS=100 scripts/scenario1-stock-decrease-lock.js
```

| 환경변수      | 기본값                                 | 용도                      |
|---------------|----------------------------------------|---------------------------|
| `TEST_MODE`   | `explore`                              | `explore` 또는 `compare`  |
| `VUS`         | 없음 (compare 모드 필수)               | 비교 실행의 동시 VU 수    |
| `DURATION`    | `1m`                                   | 비교 실행의 측정 시간     |
| `BASE_URL`    | `http://localhost:8085`                | product 서비스 주소       |
| `PRODUCT_ID`  | `10000000-0000-0000-0000-000000000001` | 시드 데이터의 테스트 상품 |
| `RESULT_FILE` | `results/<모드>-<시각>.json`           | 결과 파일 경로 지정       |

리팩토링 전/후 측정 사이에는 시드 SQL을 다시 실행해 시작 조건을 동일하게 맞춥니다.