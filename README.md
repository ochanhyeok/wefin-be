# Wefin Backend

AI 금융 뉴스 브리핑 및 모의 투자 서비스 백엔드 시스템

배포: [https://wefin.ai.kr](https://wefin.ai.kr)

개발기간: 2026.03 ~ 2026.04 

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

