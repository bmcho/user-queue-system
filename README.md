# 🕒 User-Queue-System

대규모 트래픽 상황(신규 게임 서버 오픈, 한정판 구매, 이벤트 페이지 등)에서  
사용자 요청을 **선착순 대기열**로 제어하고 순번에 따라 입장을 허용하는 Spring Boot 예제입니다.

> **Live DEMO 시나리오**
> 1. `/waiting-room?queue=default&user_id=101&redirect_url=/home` 접속
> 2. 대기 순번 표시 → 스케줄러가 허용되면 자동으로 `/home`으로 이동
> 3. 백오피스(API 또는 스케줄러)가 3명씩 입장을 허용

---

## 📑 핵심 기능 & 흐름

```text
┌──────────────┐         POST /queue            ┌───────────────┐
│  Client      │  ───────── register ─────────▶ │  Redis ZSET   │
│  (Browser)   │                                │  wait:<queue> │
└──────────────┘                                └───────────────┘
       ▲                                             │`
       │ GET /rank                                   │  ZPOP
       │ GET /allowed                                ▼
┌──────────────┐        allow (Scheduler/API)   ┌───────────────┐
│ Waiting room │ ◀────────────────────────────► │  Redis ZSET   │
│ (Thymeleaf)  │                                │ proceed:<q.>  │
└──────────────┘                                └───────────────┘
```

| 단계 | 설명 |
|------|------|
| **① 대기열 등록**<br>`POST /api/v1/queue` | Redis `users:queue:{queue}:wait` (ZSET)에 가입 시각을 score로 삽입 & 내 순번 반환 |
| **② 대기 페이지**<br>`/waiting-room` | 순번을 주기적으로 조회하여(REST) 입장 허용 시 `redirect_url`로 이동 |
| **③ 입장 허용**<br>`/queue/allow` 또는 스케줄러 | `wait` ZSET에서 N명 `ZPOP` → `proceed` ZSET으로 이동, 동시에 토큰 발급 |
| **④ 토큰 검증**<br>`/queue/allowed` | 사용자는 `/touch`로 토큰 쿠키를 발급받고, 입장 시 토큰으로 최종 검증 |

> ✔️ ZSET을 사용하므로 **O(log N)** 정렬·조회, **Reactive Redis**로 논블로킹 처리  
> ✔️ `@Scheduled` 동작 여부는 `application.yaml > scheduler.enabled`로 on/off

---

## 🗂️ 프로젝트 구조

```text
user-queue-system/
├── build.gradle              # 멀티 모듈 루트 (Gradle 8.x)
├── settings.gradle           # include "x-flow"
└── x-flow/                   # API & Waiting‑Room
    ├── src/main/java/com/bmcho/xflow
    │   ├── controller        # REST + Thymeleaf Controller
    │   ├── service           # Queue 로직 (Redis ZSET)
    │   ├── dto               # API 응답 VO (Record)
    │   └── exception         # 공통 오류 처리
    ├── src/main/resources
    │   ├── templates/        # waiting-room.html
    │   └── application.yaml
    └── build.gradle
```

> 추가 폴더 **x-website/** 는 단순 Spring MVC 샘플 페이지(독립 프로젝트)로,  
> 실동작과는 무관합니다.

---

## 🔧 기술 스택

| 범주 | 사용 기술 |
|------|-----------|
| Core  | **Spring Boot 3.5**, Spring WebFlux, Reactive Redis |
| DB    | Redis 7 (ZSET, SCAN) |
| View  | Thymeleaf 3 |
| Build | Gradle 8, Java 17 Toolchain |
| Test  | JUnit 5, **embedded‑redis** (메모리 Redis) |

---

## 🚀 빠른 시작 (로컬)

### 1) Redis 준비

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

### 2) 애플리케이션 실행

```bash
cd user-queue-system
./gradlew :x-flow:bootRun
# 기본 포트 9010, profile=local
```

### 3) 사용 예시

```bash
# ① 대기열 등록 (user_id=100)
curl -X POST "http://localhost:9010/api/v1/queue?queue=default&user_id=100"
# → {"rank":1}

# ② 3명 허용
curl -X POST "http://localhost:9010/api/v1/queue/allow?queue=default&count=3"
# → {"requestCount":3,"allowedCount":1}

# ③ 순번 조회
curl "http://localhost:9010/api/v1/queue/rank?queue=default&user_id=100"
```

> **TIP** 스케줄러 자동 허용을 사용하려면 `application.yaml`의  
> `scheduler.enabled: true`로 두고 주기·허용 인원(count)을 `UserQueueService#scheduleAllowUser`에서 조정하세요.

---

## 🌐 REST API 요약

| Method | URI | Query String | 설명 |
|--------|-----|--------------|------|
| `POST` | `/api/v1/queue` | `queue`, `user_id` | 대기열 등록 & 순번 반환 |
| `POST` | `/api/v1/queue/allow` | `queue`, `count` | 대기열에서 N명 허용 |
| `GET`  | `/api/v1/queue/rank` | `queue`, `user_id` | 현재 순번 조회 |
| `GET`  | `/api/v1/queue/touch` | `queue`, `user_id` | 토큰 쿠키 발급 |
| `GET`  | `/api/v1/queue/allowed` | `queue`, `user_id`, `token` | 입장 가능 여부 |
| `GET`  | `/waiting-room` | `queue`, `user_id`, `redirect_url` | 대기 페이지(HTML) |

---

## 🛠️ 설계 포인트

1. **토큰 기반 입장 검증**  
   동일 사용자가 브라우저 새로고침만으로 대기열을 건너뛰지 못하도록  
   `SHA‑256(user-queue-{queue}-{userId})` 기반 토큰을 쿠키로 발급·검증합니다.

2. **멀티 대기열 지원**  
   Redis 키에 `{queue}` 변수를 사용 (`users:queue:{queue}:wait`)  
   → 서비스 인스턴스 1개로 여러 이벤트 대기열 동시 운영 가능.

3. **Reactive Non‑Blocking**  
   `ReactiveRedisTemplate`·Project Reactor로 트래픽 피크 상황에서도  
   스레드 풀 증설 없이 적은 리소스로 처리합니다.

4. **Embedded Redis 테스트**  
   CI나 로컬 테스트 시 외부 Redis 의존성 없이 단위 테스트를 실행합니다.

---

## ⚠️ 실제 서비스 적용 시 고려 사항

| 항목 | 보강 내용 |
|------|-----------|
| **보안** | 토큰 위·변조 방지(HMAC 서명), HTTPS, CORS |
| **대기열 공정성** | IP/계정당 중복 등록 방지, BOT 감지(Recaptcha) |
| **모니터링** | 허용률·대기 인원·실패률 Prometheus / Grafana 지표 |
| **백오피스** | 관리자 대시보드(WebSocket)로 실시간 제어 |
| **고가용성** | Redis Cluster / Sentinel, 앱 다중 인스턴스 |

---

### ⚠️ 참고 사항

⚠️ 이 프로젝트는 학습 및 구조 설계 목적의 예제입니다.

실 서비스 적용 시에는 인증, 보안, 장애 복구, 데이터 일관성 등을 추가로 고려해야 합니다.  




