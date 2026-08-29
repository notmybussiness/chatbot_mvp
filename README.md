# Sionic AI Chatbot MVP

Spring Boot + Kotlin 기반의 챗봇 API MVP입니다.

## 1. 기술 스택
- Kotlin 1.9.25
- Spring Boot 3.4.3
- Spring Security (JWT)
- Spring Data JPA
- H2 (기본), PostgreSQL 15+ (옵션)
- OpenAI / Mock AI 전략 패턴
- Spring Scheduling (항공권 가격 데일리 배치)

## 2. 실행 방법

### 2.1 로컬 실행 (H2 기본)
```bash
./gradlew bootRun
```

### 2.2 테스트 실행
```bash
./gradlew test
```

### 2.3 PostgreSQL 실행 (옵션)
```bash
SPRING_PROFILES_ACTIVE=postgres ./gradlew bootRun
```

### 2.4 브라우저 데모 페이지
서버 실행 후 브라우저에서 아래 주소로 접속하면 회원가입/로그인/채팅/스레드/피드백을 바로 테스트할 수 있습니다.

- `http://localhost:8080/`

### 2.5 Playwright E2E 실행
```bash
npm install
npm run e2e:install
npm run e2e
```

Playwright는 충돌을 피하기 위해 내부적으로 `http://127.0.0.1:18080` 포트에서 앱을 띄워 검증합니다.

브라우저를 띄운 상태(헤디드)로 확인하려면:
```bash
npm run e2e:headed
```

`anitgravity` 명령 alias로도 동일하게 실행됩니다:
```bash
npm run anitgravity:e2e
```

## 3. 환경 변수
- `JWT_SECRET` (기본값 존재, 운영에서는 반드시 교체)
- `AI_PROVIDER` (`mock` | `openai`)
- `OPENAI_API_KEY` (openai 사용 시 필수)
- `OPENAI_MODEL` (기본: `gpt-3.5-turbo`)
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` (postgres 사용 시)

### 항공권 데일리 리포트
- `FLIGHT_PROVIDER` (`mock` | `naver`, 기본: `mock`)
- `FLIGHT_REPORT_ENABLED` (`true`면 배치 스케줄 활성화, 기본: `false`)
- `FLIGHT_REPORT_CRON` (기본: `0 0 8 * * *`), `FLIGHT_REPORT_ZONE` (기본: `Asia/Seoul`)
- `FLIGHT_HISTORY_DAYS` (최저가 비교 기간, 기본: `30`)
- `FLIGHT_REQUEST_DELAY_MS` (노선 간 요청 간격, 기본: `3000`)
- `FLIGHT_PRICE_KEYS` 등 `flight.extractor.*` (응답 파싱 후보 필드명)
- `NAVER_FLIGHT_TEMPLATE_OW`, `NAVER_FLIGHT_TEMPLATE_RT` (요청 본문 템플릿 경로)
- `NAVER_FLIGHT_BASE_URL`, `NAVER_FLIGHT_SEARCH_PATH`, `NAVER_FLIGHT_USER_AGENT`, `NAVER_FLIGHT_TIMEOUT`

## 4. API 요약

### 인증
- `POST /api/auth/signup` 회원가입
- `POST /api/auth/login` 로그인(JWT 발급)

### 채팅
- `POST /api/chats` 대화 생성
  - `isStreaming=true`면 폴링 기반 상태 조회 방식
  - `model` 파라미터로 모델 지정 가능
- `GET /api/chats/{chatId}/status` 대화 생성 상태 조회
- `GET /api/chats/threads` 스레드 단위 대화 목록 조회(페이지네이션/정렬)
- `DELETE /api/chats/threads/{threadId}` 스레드 삭제

### 피드백
- `POST /api/feedbacks` 피드백 생성
- `GET /api/feedbacks` 피드백 목록 조회(페이지네이션/정렬/긍부정 필터)
- `PATCH /api/feedbacks/{id}/status` 피드백 상태 변경(ADMIN)

### 분석
- `GET /api/analytics/activity` 24시간 사용자 활동 기록(ADMIN)
- `GET /api/analytics/report` 24시간 CSV 보고서 생성(ADMIN)

### 항공권 데일리 리포트
- `POST /api/flights/watches` 추적 노선 등록
- `GET /api/flights/watches` 추적 노선 목록(페이지네이션/정렬, 관리자는 전체 조회)
- `PATCH /api/flights/watches/{watchId}` 목표가/활성 여부 수정
- `DELETE /api/flights/watches/{watchId}` 추적 노선 삭제(가격 이력도 함께 삭제)
- `GET /api/flights/report/daily?date=YYYY-MM-DD` 데일리 리포트 조회(누적 스냅샷 기반, 외부 조회 없음)
- `POST /api/flights/report/run` 수집 + 리포트 전달 즉시 실행(ADMIN)
- `POST /api/flights/diagnostics` 연동 점검 - 실제 요청/응답 원본 확인(ADMIN)

## 5. 항공권 데일리 리포트

매일 정해진 시각에 등록된 노선의 최저가를 수집해 스냅샷으로 쌓고, 직전 대비 등락과 최근 최저가를
계산한 리포트를 만들어 **사용자의 챗봇 스레드에 대화로 전달**합니다. 요약문은 기존 `AiClient`가 생성합니다.

```
FlightWatch(추적 노선) ──▶ FlightSearchClient(mock|naver) ──▶ PriceSnapshot(일별 최저가)
                                  │                                    │
                        FlightOfferExtractor              DailyFlightReportService
                        (스키마 비의존 파싱)                          │
                                                    AiClient 요약 ──┴──▶ ReportDeliveryPort
                                                                              │
                                                                        챗봇 스레드
```

수집(`PriceCollectionService`)과 리포트 작성(`DailyFlightReportService`)을 분리했습니다.
수집만 외부에 의존하고, 리포트는 이미 쌓인 스냅샷만 읽으므로 **조회 시 외부 호출이 전혀 발생하지 않고
과거 날짜 기준으로도 다시 만들 수 있습니다.**

### 5.1 빠른 확인 (API 키 불필요)
```bash
# 1) 노선 등록
curl -X POST localhost:8080/api/flights/watches \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"origin":"ICN","destination":"NRT","departureDate":"2026-12-20","targetPrice":300000}'

# 2) 수집 + 리포트 전달 즉시 실행 (ADMIN)
curl -X POST localhost:8080/api/flights/report/run -H "Authorization: Bearer $ADMIN_TOKEN"

# 3) 리포트 조회
curl localhost:8080/api/flights/report/daily -H "Authorization: Bearer $TOKEN"
```
`GET /api/chats/threads` 로도 같은 리포트를 대화 형태로 확인할 수 있습니다.

### 5.2 네이버 연동 붙이는 법

`FLIGHT_PROVIDER=naver`는 **네이버가 공개하지 않은 내부 API를 직접 호출**합니다.
스키마가 문서화되어 있지 않으므로, 요청과 응답 양쪽 모두 코드가 아니라 **설정으로 교정**하도록 만들었습니다.

**1) 요청 본문 — 템플릿 파일 교체 (재컴파일 불필요)**

브라우저에서 `flight.naver.com` 검색 후 DevTools > Network 에서 `searchFlights` 요청의
Payload 를 복사해, 아래 파일을 통째로 교체하고 가변값만 플레이스홀더로 바꾸면 됩니다.

- `src/main/resources/flight/naver-search-request-oneway.json` (편도)
- `src/main/resources/flight/naver-search-request-roundtrip.json` (왕복)

사용 가능한 플레이스홀더: `{{origin}}` `{{destination}}` `{{departureDate}}`(yyyyMMdd)
`{{departureDateDash}}`(yyyy-MM-dd) `{{returnDate}}` `{{returnDateDash}}` `{{adults}}`
`{{cabin}}`(Y/C/F) `{{tripType}}`(OW/RT)

`NAVER_FLIGHT_TEMPLATE_OW=file:/path/to/custom.json` 처럼 외부 파일을 가리킬 수도 있습니다.
`_` 로 시작하는 키는 설명용으로 간주해 전송 전에 제거됩니다.

**2) 응답 파싱 — 후보 필드명 추가 (재컴파일 불필요)**

`FlightOfferExtractor`는 정해진 경로가 아니라 **필드 이름**으로 가격을 찾습니다.
JSON 트리 전체를 훑으면서 가격 후보 키를 가진 객체를 수집하고, 항공사·통화·링크는 상위 노드에서
물려받습니다. 필드가 어디로 옮겨가도 이름만 유지되면 동작합니다.
이름까지 바뀌었다면 `FLIGHT_PRICE_KEYS` 등에 키를 추가하면 됩니다.

**3) 확인 — 진단 엔드포인트**

```bash
curl -X POST "localhost:8080/api/flights/diagnostics?origin=ICN&destination=NRT&departureDate=2026-12-20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```
실제로 보낸 요청 본문, 받은 응답 앞부분, **가격으로 인식된 필드명**(`matchedPriceKeys`),
파싱된 항공권을 그대로 돌려줍니다. `matchedPriceKeys` 가 비어 있으면 후보 키를 넓혀야 한다는 뜻입니다.

### 5.3 알아두어야 할 한계

- **네이버는 클라우드(AWS 등) IP 대역을 차단합니다.** 로컬에서 동작하더라도 서버 배포 시 실패할 수
  있습니다. 이 저장소는 IP 우회나 차단 회피 로직을 포함하지 않습니다.
- 기본 템플릿의 필드명은 확정값이 아닙니다. 실제 연동 전에 5.2의 DevTools 캡처로 대조해야 합니다.
- 수집이 실패하면 해당 노선만 실패로 기록되고 나머지는 계속 진행됩니다. 리포트에 `missingCount` 로 표시됩니다.

공식 경로가 필요하면 `FlightSearchClient` 구현체를 추가하고 `FLIGHT_PROVIDER` 만 바꾸면 됩니다.
조사 시점 기준 대안은 아래와 같습니다.

| 소스 | 상태 |
|---|---|
| Skyscanner Travel API | 파트너 심사 + 상업 계약 필요, 셀프서비스 없음 |
| Amadeus Self-Service | 셀프서비스 포털이 2026-07-17 종료되어 무료 티어 없음 |
| Travelpayouts(Aviasales) Data API | 무료 가입 가능, 캐시 가격이라 실시간 예약엔 부적합하나 추이 리포트에는 적합 |
| SerpApi Google Flights | 유료, 한국 노선 커버리지 양호 |

## 6. 요구사항 반영 요약
- 회원가입/로그인/JWT 인증: 구현 완료
- chat/thread 30분 기준 스레드 유지/생성: 구현 완료
- 사용자 권한 기반 조회/삭제 제어: 구현 완료
- 피드백 중복 방지(userId+chatId) 및 소유권 검증: 구현 완료
- 관리자 전용 분석/CSV 보고서: 구현 완료
- 확장 가능한 AI 전략 인터페이스(AiClient): 구현 완료
- (스펙 외 확장) 항공권 가격 데일리 리포트: 구현 완료 — 기존 도메인을 수정하지 않고
  새 도메인 + 새 전략 인터페이스(FlightSearchClient)를 얹어 "지속적 확장 가능" 요건을 검증했습니다.

## 7. 과제 분석
- 시연 목표가 "API로 AI 활용 가능"에 집중되어 있어, UI보다 백엔드 API 안정성과 권한 모델을 우선했습니다.
- 긴급 시연 상황을 고려해 `mock` provider를 기본값으로 두고, API 키 없이도 즉시 시연 가능하게 설계했습니다.
- 동시에 OpenAI 연동 코드를 분리해 운영 전환 시 설정만 바꾸면 동작하도록 구성했습니다.

## 8. AI 활용 방식과 어려움
- AI는 요구사항 추적, 누락 케이스(권한/소유권/정렬/페이지네이션) 점검, 테스트 시나리오 보강에 활용했습니다.
- 주요 어려움은 모듈 통합 시점의 크로스 도메인 의존성(Feedback↔Chat, Analytics↔User/Chat)과 테스트 데이터 정합성(FK/정리 순서)이었습니다.
- 해결 방식은 서비스 계층에서 소유권 검증을 명시하고, 통합 테스트에서 실제 JWT + 실제 엔티티 흐름을 재현하는 방식으로 안정화했습니다.

## 9. 가장 구현이 어려웠던 기능
- **채팅 스레드/스트리밍 상태 관리**가 가장 어려웠습니다.
- 이유:
  - 30분 기준 스레드 재사용 규칙
  - `isStreaming` 옵션에 따른 비동기 응답 처리
  - 폴링 상태 조회 시 권한 검증과 완료 상태 동기화
- 해결:
  - 스레드 선택 로직을 서비스에서 단일화
  - 스트리밍 요청은 임시 응답(`Thinking...`) 후 비동기 완료 저장
  - 상태 조회에서 메모리 응답과 DB 응답을 일관되게 병합

## 10. 향후 확장 포인트
- RAG 연동 (Vector DB + Retrieval 단계 추가)
- SSE/WebSocket 기반 실시간 스트리밍으로 전환
- 감사 로그/모니터링/레이트리밋 강화
- 항공권 데이터 소스를 공식 API(Travelpayouts/SerpApi 등) 구현체로 교체
- 리포트 전달 채널 확장 (ReportDeliveryPort 구현체 추가: 이메일/슬랙)
