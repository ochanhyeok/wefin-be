# Fork 변경 기록

이 레포는 팀 프로젝트 [S0L-V/wefin-be](https://github.com/S0L-V/wefin-be)의 개인 포크입니다.
팀 레포와 운영 환경에 영향을 주지 않고, 포트폴리오용 개선 작업(Redis 멱등성, 인덱스 벤치마크 등)을 실험하기 위해 만들었습니다.

- 포크 일자: 2026-11-08
- 작업 브랜치: `portfolio/redis-index`
- 원본 기준 커밋: 원본 `dev` `fa8f2ac4`(2026-06-04)까지 반영 (2026-11-08 merge, 아래 "원본 동기화 기록" 참고)

## 삭제한 것

| 대상 | 원래 역할 | 삭제 이유 |
|------|----------|----------|
| `.github/workflows/backend-cd.yml` | 운영 서버 배포 | 포크에서 실행되면 운영 서버 배포를 시도할 수 있음 |
| `.github/workflows/backend-cd-desktop.yml` | 데스크톱(온프레미스) 배포, Self-hosted Runner 사용 | 위와 같음. 팀 Runner·서버와 연결될 여지를 없앰 |
| `.github/workflows/discord-dev-updated.yml` | dev 브랜치 갱신 시 팀 Discord 알림 | 개인 작업 알림이 팀 채널로 가지 않게 함 |
| `.github/workflows/discord-pr-approved.yml` | PR 승인 시 팀 Discord 알림 | 위와 같음 |
| `.github/workflows/discord-ready-for-review.yml` | 리뷰 요청 시 팀 Discord 알림 | 위와 같음 |
| `.github/workflows/pr-assignment.yml` | PR 리뷰어 자동 할당 | 개인 레포에서는 팀원을 리뷰어로 지정할 필요가 없음 |

`backend-ci.yml`(빌드·테스트)은 운영에 영향이 없어 남겨두었습니다.

## 변경한 것

| 대상 | 변경 내용 | 이유 |
|------|----------|------|
| `application-local.yml`의 `wefin-news.api.base-url` | 고정값 → `${WEFIN_NEWS_BASE_URL:https://dev-api.wefin.ai.kr}` | 팀 개발 서버 대신 다른 주소를 쓸 수 있게 함. 기본값은 그대로라 뉴스 기능은 지금처럼 동작함 |

## 레포 설정

- **GitHub Actions 비활성화**: 포크의 Settings → Actions에서 꺼둠. `dev` 등 다른 브랜치에는 원본 워크플로가 그대로 남아 있어서, Actions를 켜면 그 브랜치에서 실행될 수 있음
- **remote**: 로컬에서 `origin`은 팀 레포, `fork`는 이 레포. 팀 레포에는 push하지 않음

## 직접 지켜야 할 것

- `.env`의 DB 접속 정보는 **로컬 DB**로 둘 것. 팀 DB를 가리키면 Flyway가 `V54`, `V55` 마이그레이션을 팀 DB에 실행함
- 원본 변경을 가져올 때 `V54`, `V55`와 번호가 같은 마이그레이션이 있으면 번호를 바꿔야 함

## 원본 동기화 기록

| 날짜 | 내용 |
|------|------|
| 2026-11-08 | 작업 브랜치가 오래된 `dev`(PR #330 머지 시점)에서 시작해 PR #1에서 충돌 발생. 원본 `dev`를 merge해서 해결. 원본에서 수정된 `backend-cd.yml`은 이 포크 방침대로 삭제 유지. 원본의 `V53` 마이그레이션이 들어왔지만 `V54`, `V55`와 번호 충돌은 없음 |

## 되돌리기

삭제한 워크플로는 원본에 그대로 있으므로 필요하면 복원할 수 있습니다.

```bash
git checkout origin/dev -- .github/workflows/
```
