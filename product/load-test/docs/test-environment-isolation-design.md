# 테스트 환경 데이터 저장소 분리 설계

- 관련 이슈: #9
- 적용 대상: 개발 환경, Spring Boot 테스트, k6 부하 테스트

---

## 배경

기존 환경은 테스트 목적별 데이터 저장 공간이 일관되게 분리되어 있지 않았습니다.

- 개발 서비스는 Docker MySQL의 `hubEleven` 스키마와 Redis DB 0을 사용했습니다.
- Spring Boot 테스트는 Homebrew MySQL의 `hubeleven_test` 스키마를 사용했지만 Redis는 개발 환경과 동일한 DB 0을 사용했습니다.
- k6 부하 테스트는 Docker MySQL의 개발용 `hubEleven` 스키마와 Redis DB 0을 사용했습니다.

이 구조에서는 k6 시드 데이터가 개발 데이터와 섞이고, Spring Boot 테스트와 개발 서비스의 Redis 락 키가 충돌할 수 있습니다. 또한 Spring Boot 테스트가 Homebrew MySQL과 Docker Redis를 함께 사용해 실행 환경을 파악하기 어려웠습니다.

## 결정

MySQL과 Redis 컨테이너를 환경별로 추가하지 않고, 기존 Docker 인스턴스 안에서 데이터 저장 공간을 논리적으로 분리합니다.

| 용도 | MySQL 스키마 | Redis 논리 DB |
|---|---|---|
| 개발 환경 | `hubeleven` | DB 0 |
| Spring Boot 테스트 | `hubeleven_test` | DB 1 |
| k6 부하 테스트 | `hubeleven_loadtest` | DB 2 |

MySQL 스키마명은 운영체제와 MySQL 설정에 따른 대소문자 차이를 피하기 위해 모두 소문자로 통일합니다.

## 저장 구조

MySQL 스키마는 같은 MySQL 프로세스와 Docker 볼륨을 사용하지만 서로 다른 테이블 이름 공간을 제공합니다.

~~~text
Docker MySQL
└── mysql_data
    ├── hubeleven
    ├── hubeleven_test
    └── hubeleven_loadtest
~~~

Redis 논리 DB도 같은 Redis 프로세스와 볼륨을 사용하지만 번호별로 키 공간을 분리합니다.

~~~text
Docker Redis
└── redis_data
    ├── DB 0: 개발 키
    ├── DB 1: Spring Boot 테스트 키
    └── DB 2: k6 부하 테스트 키
~~~

## 대안 검토

### 환경별 컨테이너 분리

MySQL과 Redis 컨테이너를 개발, Spring Boot 테스트, k6 부하 테스트 용도로 각각 실행하는 방안입니다.

장점:

- 데이터뿐만 아니라 프로세스, 커넥션, 메모리 설정을 환경별로 분리할 수 있습니다.
- 부하 테스트 중 다른 환경의 요청이 같은 MySQL 또는 Redis 프로세스를 사용하는 것을 막을 수 있습니다.

단점:

- 환경별 포트, 볼륨, 초기화 설정과 실행 절차가 추가됩니다.
- 여러 인스턴스를 동시에 실행하면 로컬 메모리 사용량이 증가합니다.
- 현재처럼 한 명이 각 작업을 순차 실행하는 환경에서는 자원 격리의 이점이 제한적입니다.

### Testcontainers

테스트 실행 시 MySQL과 Redis 컨테이너를 자동으로 생성하고 종료하는 방안입니다.

장점:

- 테스트마다 초기 상태를 만들 수 있습니다.
- 로컬과 CI에서 동일한 인프라 버전을 사용할 수 있습니다.
- 테스트 종료 후 데이터가 남지 않습니다.

단점:

- 테스트 의존성과 인프라 실행 코드가 추가됩니다.
- Docker 실행 환경과 이미지 준비가 필요합니다.
- 현재 문제인 k6 시드와 개발 데이터의 혼합을 해결하려면 별도의 부하 테스트 환경 설계가 추가로 필요합니다.

## 논리적 분리를 선택한 이유

현재 해결해야 하는 문제는 여러 사용자의 동시 실행이나 서버 자원 간섭보다 테스트 데이터와 개발 데이터의 혼합입니다.

- 프로젝트를 한 명이 로컬에서 사용하며 개발, Spring Boot 테스트, k6 부하 테스트를 순차적으로 실행합니다.
- MySQL 스키마와 Redis 논리 DB 분리만으로 현재 필요한 데이터 및 키 격리를 달성할 수 있습니다.
- 기존 Docker 인스턴스를 재사용해 추가 포트와 볼륨 관리 없이 Spring Boot 테스트 인프라를 Docker Compose 기준으로 통일할 수 있습니다.
- 별도 컨테이너 방식으로 전환할 수 있는 경로를 유지하면서 현재 필요한 수준보다 복잡한 구성을 먼저 도입하지 않습니다.

따라서 현재 프로젝트 규모와 실행 방식에서는 논리적 분리가 비용 대비 적절한 선택이라고 판단했습니다.

## 한계와 측정 조건

논리적 분리는 데이터와 키를 분리하지만 다음 자원은 공유합니다.

- MySQL CPU, 메모리, 커넥션과 InnoDB 버퍼 풀
- Redis CPU, 메모리, 네트워크 이벤트 처리 자원
- Docker Desktop에 할당된 로컬 머신 자원

따라서 k6 개선 전후 측정 시에는 개발 서비스와 Spring Boot 테스트를 동시에 실행하지 않습니다. 동일한 MySQL 및 Redis 설정, 시드 데이터, VU, 실행 시간과 측정 절차를 사용합니다.

Redis 논리 DB 번호는 Redisson 기본 설정에 자동 반영되지 않습니다. 커스텀 `RedissonConfig`가 `spring.data.redis.database` 값을 읽고 `SingleServerConfig.setDatabase()`에 전달해야 합니다.

## 데이터 이전 및 정리 원칙

- 기존 `hubEleven` 데이터는 백업 후 `hubeleven`으로 이전합니다.
- 원본과 대상의 테이블 행 수 및 체크섬을 확인하기 전에는 기존 스키마를 삭제하지 않습니다.
- Homebrew MySQL의 `hubeleven_test`는 Docker 기반 Spring Boot 테스트가 통과한 후 `DROP DATABASE`로 삭제합니다.
- 격리 전에 측정한 k6 결과는 초기 탐색 결과로 취급하고, 공식 baseline은 격리 환경에서 다시 측정합니다.

## 검증 기준

- 개발 서비스는 MySQL `hubeleven`, Redis DB 0을 사용합니다.
- Spring Boot 테스트는 MySQL `hubeleven_test`, Redis DB 1을 사용합니다.
- k6 부하 테스트용 product 서비스는 MySQL `hubeleven_loadtest`, Redis DB 2를 사용합니다.
- Spring Boot 테스트 종료 후 개발용 MySQL 데이터와 Redis 키가 변경되지 않습니다.
- k6 시드 및 부하 테스트 종료 후 개발용 MySQL 데이터와 Redis 키가 변경되지 않습니다.
- 격리 환경에서 동일 조건으로 실행한 baseline 결과가 반복 측정 가능한 범위에 있는지 확인합니다.

## 별도 인스턴스로 전환할 조건

다음 조건이 생기면 논리적 분리 대신 환경별 컨테이너 또는 Testcontainers 도입을 다시 검토합니다.

- 여러 사용자가 개발과 부하 테스트를 동시에 실행하는 경우
- 공유 MySQL 또는 Redis 자원 간섭으로 측정 편차가 커지는 경우
- CI에서 별도의 통합 테스트 인프라 수명주기 관리가 필요한 경우
- 환경별 MySQL 또는 Redis 버전과 서버 설정을 다르게 운영해야 하는 경우
- Redis Cluster처럼 논리 DB 분리를 사용할 수 없는 환경으로 전환하는 경우
