# Sionic AI Chatbot MVP

Spring Boot + Kotlin 기반의 챗봇 API MVP입니다.

## 1. 기술 스택
- Kotlin 1.9.25
- Spring Boot 3.4.3
- Spring Security (JWT)
- Spring Data JPA
- H2 (기본), PostgreSQL 15+ (옵션)
- OpenAI / Mock AI 전략 패턴

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

## 5. 요구사항 반영 요약
- 회원가입/로그인/JWT 인증: 구현 완료
- chat/thread 30분 기준 스레드 유지/생성: 구현 완료
- 사용자 권한 기반 조회/삭제 제어: 구현 완료
- 피드백 중복 방지(userId+chatId) 및 소유권 검증: 구현 완료
- 관리자 전용 분석/CSV 보고서: 구현 완료
- 확장 가능한 AI 전략 인터페이스(AiClient): 구현 완료

## 6. 과제 분석
- 시연 목표가 "API로 AI 활용 가능"에 집중되어 있어, UI보다 백엔드 API 안정성과 권한 모델을 우선했습니다.
- 긴급 시연 상황을 고려해 `mock` provider를 기본값으로 두고, API 키 없이도 즉시 시연 가능하게 설계했습니다.
- 동시에 OpenAI 연동 코드를 분리해 운영 전환 시 설정만 바꾸면 동작하도록 구성했습니다.

## 7. AI 활용 방식과 어려움
- AI는 요구사항 추적, 누락 케이스(권한/소유권/정렬/페이지네이션) 점검, 테스트 시나리오 보강에 활용했습니다.
- 주요 어려움은 모듈 통합 시점의 크로스 도메인 의존성(Feedback↔Chat, Analytics↔User/Chat)과 테스트 데이터 정합성(FK/정리 순서)이었습니다.
- 해결 방식은 서비스 계층에서 소유권 검증을 명시하고, 통합 테스트에서 실제 JWT + 실제 엔티티 흐름을 재현하는 방식으로 안정화했습니다.

## 8. 가장 구현이 어려웠던 기능
- **채팅 스레드/스트리밍 상태 관리**가 가장 어려웠습니다.
- 이유:
  - 30분 기준 스레드 재사용 규칙
  - `isStreaming` 옵션에 따른 비동기 응답 처리
  - 폴링 상태 조회 시 권한 검증과 완료 상태 동기화
- 해결:
  - 스레드 선택 로직을 서비스에서 단일화
  - 스트리밍 요청은 임시 응답(`Thinking...`) 후 비동기 완료 저장
  - 상태 조회에서 메모리 응답과 DB 응답을 일관되게 병합

## 9. 향후 확장 포인트
- RAG 연동 (Vector DB + Retrieval 단계 추가)
- SSE/WebSocket 기반 실시간 스트리밍으로 전환
- 감사 로그/모니터링/레이트리밋 강화
