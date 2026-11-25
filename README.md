# Wefin Backend

AI 금융 뉴스 브리핑 및 모의 투자 서비스 백엔드 시스템

배포: [https://wefin.ai.kr](https://wefin.ai.kr)

개발기간: 2026.03 ~ 2026.04 

<br>



## 이 포크에서 바꾼 것 (오찬혁, 2026.11)

팀 프로젝트([S0L-V/wefin-be](https://github.com/S0L-V/wefin-be))에서 제가 맡은 주문·체결·자산 모듈을, 장애와 동시 요청 상황 기준으로 다시 설계한 개인 포크입니다.
결정 과정과 버린 대안은 ADR에, 코드 변경은 PR에 있습니다. 팀 프로젝트 원래 코드에서 무엇을 어떤 판단으로 바꿨는지는 [변경 기록](docs/design-history.md)에 다이어그램과 함께 정리했습니다.

| 주제 | 핵심 | 근거 |
|---|---|---|
| 지정가 매칭 인덱스 | 100만 건에서 89ms → 0.45ms, 부분 인덱스로 크기 1/9. 운영 테이블 무중단 적용(CONCURRENTLY) 중 Flyway 멈춤 해결 | [PR #2](https://github.com/ochanhyeok/wefin-be/pull/2) |
| 주문 멱등성 재설계 | 계좌별 키, 요청 지문(422), 첫 응답 재전송, 거절(4xx)·장애(5xx) 구분, 처리 중 서버 다운 시 리스 | [ADR 0001](docs/adr/0001-order-idempotency.md) · [PR #3](https://github.com/ochanhyeok/wefin-be/pull/3) |
| 현금 원장과 일일 대사 | 잔고를 바꾸는 10개 경로를 원장에 기록, 계좌·주문 대사, 배치의 동시 실행·중단·실패 처리 | [ADR 0002](docs/adr/0002-cash-ledger-reconciliation.md) · [PR #4](https://github.com/ochanhyeok/wefin-be/pull/4) |
| 외부 시세 API 장애 격리 | 트랜잭션 밖 조회, 타임아웃, 서킷 브레이커. 한투 무응답 시 무관한 조회 최대 4,896ms → 30ms | [ADR 0003](docs/adr/0003-market-api-isolation.md) · [PR #5](https://github.com/ochanhyeok/wefin-be/pull/5) |

```bash
./gradlew test        # 927개, Docker 필요 (Testcontainers PostgreSQL·Redis)
./gradlew benchmark   # 락 전략·인덱스·시세 장애 측정, 결과는 build/benchmark-results/
```

<br>

## Contributors

| <img src="https://avatars.githubusercontent.com/u/249074764?v=4" width="80" /> | <img src="https://avatars.githubusercontent.com/u/233912143?v=4" width="80" /> | <img src="https://avatars.githubusercontent.com/u/118364543?v=4" width="80" /> | <img src="https://avatars.githubusercontent.com/u/125783546?v=4" width="80" /> | <img src="https://avatars.githubusercontent.com/u/88781949?v=4" width="80" /> | <img src="https://avatars.githubusercontent.com/u/233753069?v=4" width="80" /> |
| :----------------------------------------------------------------------------: | :----------------------------------------------------------------------------: | :----------------------------------------------------------------------------: | :----------------------------------------------------------------------------: | :---------------------------------------------------------------------------: | :----------------------------------------------------------------------------: |
|                                     강희민                                     |                                     김민서                                     |                                     박수진                                     |                                     오찬혁                                     |                                    이가은                                     |                                     한재훈                                     |
|                  [kkhhmm3103](https://github.com/kkhhmm3103)                   |               [minseokim0113](https://github.com/minseokim0113)                |                     [cl-o-lc](https://github.com/cl-o-lc)                      |                  [ochanhyeok](https://github.com/ochanhyeok)                   |                   [gaeunnlee](https://github.com/gaeunnlee)                   |                     [hjh79gw](https://github.com/hjh79gw)                      |
|                            인증 / 그룹 / 결제·구독                             |                           채팅 / 퀘스트 / 뉴스 공유                            |                      실시간 시세 / 매칭 엔진 / 종목 정보                       |                         주문·체결 / 포트폴리오 / 랭킹                          |                     뉴스 파이프라인 / 시장 동향 / 관심사                      |                               과거 기반 모의투자                               |

<br>



## Tech Stack

| 분류         | 기술                                                                  |
| ------------ | --------------------------------------------------------------------- |
| Language     | Java 17                                                               |
| Framework    | Spring Boot 3.5.x, Spring Security, Spring Data JPA, Spring WebSocket |
| Persistence  | PostgreSQL + pgvector, QueryDSL 5.0, Flyway                           |
| Cache        | Caffeine (in-memory)                                                  |
| AI           | OpenAI API (text-embedding-3-small, gpt-4o-mini)                      |
| Auth         | JWT (jjwt 0.11.5)                                                     |
| External API | 한국투자증권(KIS) Open API, Yahoo Finance, 한국은행, Dart                   |
| Build / Test | Gradle, JUnit 5, Testcontainers, Jacoco                               |
| Infra        | Docker, Docker Compose, Nginx, AWS EC2                                |

<br>




## Domain Overview

```
domain/
├── news/          뉴스 수집/정제/임베딩/태깅/클러스터링/요약/추천
├── trading/       계좌/주문/체결/포트폴리오/시세/랭킹/스냅샷
├── game/          과거 데이터 기반 투자 게임 (room/turn/holding/result)
├── group/         그룹 / 그룹 초대
├── chat/          AI 챗 / 그룹 챗 / 글로벌 챗 (WebSocket)
├── interest/      사용자 관심사 (STOCK / SECTOR / TOPIC)
├── market/        시장 지표 / 시장 동향
├── quest/         일일 퀘스트
├── vote/          투표
├── payment/       결제 / 구독
├── user/          사용자
└── auth/          인증 / 이메일 인증 / 리프레시 토큰
```

<br>




## Architecture


<img width="1872" height="1330" alt="Image" src="https://github.com/user-attachments/assets/5e913639-82f2-4868-94a6-a8c2424af376" />
<img width="6452" height="3900" alt="news_architecture" src="https://github.com/user-attachments/assets/658e76bb-80d9-4451-9c3d-c3d64b5d5b39" />


<br>


## ERD
| 전체 도메인 | 뉴스 도메인 |
| :---: | :---: |
|<img width="1467" height="851" alt="erd" src="https://github.com/user-attachments/assets/7f4304f2-9562-4c82-ba94-f9ed7990a38d" /> | <img width="1477" height="859" alt="erd_news" src="https://github.com/user-attachments/assets/7737264e-1e1d-425e-988e-96b930795ae5" /> |




<br>





## Getting Started

### 사전 준비

- Java 17
- Docker / Docker Compose
- OpenAI API Key, KIS Open API Key

### 로컬 실행

```bash
# 1. PostgreSQL + pgvector 기동
docker compose up -d

# 2. 환경 변수 설정 (.env 또는 export)
export OPENAI_API_KEY=sk-...
export KIS_APP_KEY=...
export KIS_APP_SECRET=...

# 3. 애플리케이션 실행
./gradlew bootRun
```

`SPRING_PROFILES_ACTIVE`를 통하여 변경 가능 (기본 profile `local`)

### 테스트

```bash
./gradlew test            # 단위 테스트 (H2)
./gradlew check           # Jacoco 커버리지 검증 포함
```

통합 테스트: Testcontainers + PostgreSQL 기반 동작

### 빌드 / 배포

```bash
./gradlew clean bootJar
docker build -t wefin-be .
docker compose -f docker-compose.prod.yml up -d
```

