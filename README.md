# AI Chatbot API

## 0) 현재 상태 한 줄 요약 (2026-03-04)

현재 브랜치 기준으로 MVP 핵심 흐름인 `인증 -> 대화 -> 피드백`은 **자동 테스트에서 실패**하며,
`관리자 분석(활동/CSV)`과 `AI 클라이언트 단위 로직`은 **일부/전부 통과** 상태입니다.

---

## 1) 현재 프로젝트 전체 Flow

### 1-1. Member API Flow (의도된 사용자 시나리오)

1. `POST /api/auth/signup`
   - `AuthController -> UserService.signUp -> UserRepository.save`
2. `POST /api/auth/login`
   - `AuthController -> UserService.login`
   - 비밀번호 검증 후 JWT 발급, `LoginLog` 저장
3. `POST /api/chats`
   - JWT 인증(`JwtAuthFilter`) 후 `ChatService.createChat`
   - 30분 규칙으로 스레드 재사용/신규 생성
   - 초기 응답 `Thinking...` 반환 후 `@Async`로 AI 응답 처리
4. `GET /api/chats/{chatId}/status`
   - 폴링으로 현재 응답 상태 확인
5. `GET /api/chats/threads`, `DELETE /api/chats/threads/{threadId}`
   - 사용자 권한별 스레드 조회/삭제
6. `POST /api/feedbacks`, `GET /api/feedbacks`
   - 대화 결과에 대한 피드백 생성/조회

### 1-2. Admin API Flow

1. `GET /api/analytics/activity`
   - `@PreAuthorize("hasRole('ADMIN')")`
   - 최근 24시간 가입/로그인/대화 건수 집계
2. `GET /api/analytics/report`
   - 최근 24시간 대화 데이터를 CSV(BOM 포함)로 다운로드

### 1-3. 공통 요청 처리 흐름

- `SecurityConfig`: `/api/auth/**`, `/h2-console/**`, `/error` 제외 인증 필요
- `JwtAuthFilter`: Bearer 토큰 파싱 -> `SecurityContext`에 사용자 ID/권한 설정
- 예외 처리: `GlobalExceptionHandler`가 공통 에러 응답(JSON) 반환

---

## 2) 테스트 기반 검증 결과

### 2-1. 실행한 명령

```bash
./gradlew test
npx playwright test e2e/mvp-flow.spec.js --reporter=line
```

### 2-2. 집계 결과

- Backend(JUnit): **38개 중 14개 통과 / 24개 실패**
- E2E(Playwright): **3개 중 1개 통과 / 2개 실패**

> 참고: E2E 스펙은 `http://localhost:8080` 고정 대상입니다.

---

## 3) MVP 기능 동작 여부 (테스트 근거)

| MVP 기능 | 동작 여부 | 테스트 근거 | 메모 |
|---|---|---|---|
| 회원가입/로그인/JWT 인증 | FAIL | `com.example.chatbot.user.UserAuthIntegrationTest` (6/6 fail), `com.example.chatbot.user.controller.UserAuthIntegrationTest` (5/5 fail), Playwright member flow 실패 | signup/login 경로에서 500 발생 |
| 대화 생성/상태 조회/스레드 관리 | FAIL | `com.example.chatbot.chat.controller.ChatIntegrationTest` (3/3 fail) | 테스트 setup의 signup 단계에서 500으로 차단 |
| 피드백 생성/조회/상태 변경 | FAIL | `com.example.chatbot.feedback.FeedbackIntegrationTest` (5/5 fail) | setUp 중 사용자 저장 단계 실패 |
| 관리자 활동/리포트 | PARTIAL | `AnalyticsServiceTest` (2/2 pass), `AnalyticsControllerTest` (2/3 pass), `AnalyticsIntegrationTest` (0/4 fail), Playwright admin flow 1건 pass | 서비스 단위는 통과, 통합은 실패 |
| AI Provider 전략/응답 파싱 | PASS | `AiClientStrategyTest` (7/7 pass), `GeminiAiClientTest` (2/2 pass) | mock/gemini 단위 동작 확인 |
| 애플리케이션 컨텍스트 로딩 | PASS | `ChatbotApplicationTests` (1/1 pass) | 기본 부팅 컨텍스트 정상 |

---

## 4) 실패 패턴 요약

- 주요 공통 예외: `ObjectOptimisticLockingFailureException` / `StaleObjectStateException`
  - 발생 지점: `UserRepository.save`, `LoginLogRepository.save` 등 엔티티 저장 단계
- 보안 기대치 불일치:
  - `AnalyticsControllerTest`에서 MEMBER 요청이 기대(403)와 다르게 200
  - 일부 테스트는 무토큰 요청 401 기대 vs 실제 403

---

## 5) 현재 결론

- **MVP 전체 시연 플로우는 아직 Green 상태가 아님**
- 특히 `인증(signUp/login)` 실패가 연쇄적으로 chat/feedback 통합 테스트 실패를 유발
- 반면, analytics의 일부 경로 및 AI 클라이언트 단위 로직은 독립적으로 동작 확인

---

## 6) 실제 구현 화면 (첨부 이미지 기준)

첨부된 `Chat Workspace` 화면은 이 저장소의 백엔드 API에 매핑되는 데모 클라이언트 예시입니다.

![Chat Workspace Flow](docs/images/chat-workspace-flow.png)

- 좌측 `Threads` 패널
  - `GET /api/chats/threads` 결과 목록
  - 항목 선택 후 `threadId` 기반 대화 맥락 유지
- 중앙 `Chat` 패널
  - 메시지 전송: `POST /api/chats`
  - 상태 폴링: `GET /api/chats/{chatId}/status`
  - 30분 내 재질문 시 동일 스레드 유지(`ChatService` 규칙)
- 우측 `Feedback` 패널
  - 생성: `POST /api/feedbacks`
  - 목록: `GET /api/feedbacks`
  - 결과값: `isPositive=true/false` (Positive/Negative)
- 상단 사용자 배지 (`MEMBER`)
  - JWT 기반 인증 + 권한 표시(`JwtAuthFilter`/SecurityContext)

> 주의: 첨부 화면은 수동 시연 관점의 구현 예시이며, 현재 브랜치 자동 테스트 결과는 위 2~5장에서 명시한 대로 Red 상태입니다.

---

## 7) 실제 구현 코드 (핵심 발췌)

### 7-1. 인증: 회원가입/로그인

```kotlin
@PostMapping("/signup")
fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<ApiResponse<UUID>> {
    val user = userService.signUp(request)
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(user.id))
}

@PostMapping("/login")
fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<ApiResponse<LoginResponse>> {
    val response = userService.login(request)
    return ResponseEntity.ok(ApiResponse.success(response))
}
```

### 7-2. 대화: 30분 스레드 규칙 + 비동기 AI 처리

```kotlin
val lastChat = chatRepository.findFirstByThreadUserIdOrderByCreatedAtDesc(userId)
val now = OffsetDateTime.now()

val thread = if (lastChat == null || lastChat.createdAt.plusMinutes(30).isBefore(now)) {
    threadRepository.save(ChatThread(user = user))
} else {
    lastChat.thread
}

val chat = chatRepository.save(Chat(
    question = request.question,
    answer = "Thinking...",
    thread = thread
))

self.processChatAsync(chat.id!!, messages, request.model)
```

### 7-3. 피드백: 중복 방지 + 저장

```kotlin
if (feedbackRepository.existsByUserIdAndChatId(userId, request.chatId)) {
    throw IllegalStateException("Feedback already exists for this chat")
}

val feedback = Feedback(
    userId = userId,
    chatId = request.chatId,
    isPositive = request.isPositive
)
val saved = feedbackRepository.save(feedback)
```

### 7-4. 분석: ADMIN 권한 보호 + CSV 리포트

```kotlin
@GetMapping("/activity")
@PreAuthorize("hasRole('ADMIN')")
fun getActivity(): ResponseEntity<ApiResponse<ActivityResponse>> {
    return ResponseEntity.ok(ApiResponse.success(analyticsService.getActivity()))
}

@GetMapping("/report")
@PreAuthorize("hasRole('ADMIN')")
fun generateReport(): ResponseEntity<ByteArray> {
    val csv = analyticsService.generateReport()
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report_${LocalDate.now()}.csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(csv)
}
```

---

## 8) 구현 위치 (파일 경로)

- 인증: `src/main/kotlin/com/example/chatbot/user/controller/AuthController.kt`, `src/main/kotlin/com/example/chatbot/user/service/UserService.kt`
- 보안/JWT: `src/main/kotlin/com/example/chatbot/common/config/SecurityConfig.kt`, `src/main/kotlin/com/example/chatbot/common/security/JwtAuthFilter.kt`
- 대화: `src/main/kotlin/com/example/chatbot/chat/controller/ChatController.kt`, `src/main/kotlin/com/example/chatbot/chat/service/ChatService.kt`
- 피드백: `src/main/kotlin/com/example/chatbot/feedback/controller/FeedbackController.kt`, `src/main/kotlin/com/example/chatbot/feedback/service/FeedbackService.kt`
- 분석/리포트: `src/main/kotlin/com/example/chatbot/analytics/controller/AnalyticsController.kt`, `src/main/kotlin/com/example/chatbot/analytics/service/AnalyticsService.kt`
