# Step 3b: Chat Management Feature (feature/chat 브랜치)

## 🎯 목표

대화(chat) 및 스레드(thread) 관리 기능을 구현합니다. 주로 `chat/` 패키지를 작업합니다.

## 📋 참조 문서

- `@prompt/TASK_STATUS.md` — 공통 작업 상태 확인
- `@Wanted_Spec/요구사항.md` — "대화(chat) 관리 기능" 섹션

## ⚠️ 규칙

- **`common/` 패키지 절대 수정 금지** (단, 필요시 AiClient 관련 인터페이스 확장은 허용이나 최소화할 것)
- `chat/` 패키지만 주로 생성/수정
- Phase 2a (User Auth)가 완료된 `main`에서 브랜치 생성

---

## 사전 작업

```bash
git checkout main
git pull
git checkout -b feature/chat
```

---

## 구현 상세

### 1. Entities

```kotlin
// chat/entity/Thread.kt
@Entity
@Table(name = "threads")
class Thread(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User, // user 패키지의 User 엔티티

) : BaseEntity()

// chat/entity/Chat.kt
@Entity
@Table(name = "chats")
class Chat(
    @Column(columnDefinition = "TEXT", nullable = false)
    val question: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val answer: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id", nullable = false)
    val thread: Thread

) : BaseEntity()
```

### 2. Repositories

```kotlin
// chat/repository/ThreadRepository.kt
interface ThreadRepository : JpaRepository<Thread, UUID> {
    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<Thread>
}

// chat/repository/ChatRepository.kt
interface ChatRepository : JpaRepository<Chat, UUID> {
    // 특정 유저의 가장 최근 대화 기록 조회용
    fun findFirstByThreadUserIdOrderByCreatedAtDesc(userId: UUID): Chat?

    // 특정 스레드에 속한 대화 목록 (과거 대화 컨텍스트 구성용)
    fun findByThreadIdOrderByCreatedAtAsc(threadId: UUID): List<Chat>
}
```

### 3. DTOs

```kotlin
// chat/dto/ChatCreateRequest.kt
data class ChatCreateRequest(
    @field:NotBlank val question: String,
    val isStreaming: Boolean = false,
    val model: String? = null
)

// chat/dto/ChatResponse.kt (동기용)
data class ChatResponse(
    val chatId: UUID,
    val threadId: UUID,
    val answer: String, // 폴링 방식의 경우 현재까지 생성된 텍스트
    val isCompleted: Boolean, // 응답 생성이 완료되었는지 여부 (폴링용)
    val createdAt: OffsetDateTime
)

// 기타 스레드 목록 조회용 DTO (ThreadResponse 등) 구현 필요
```

### 4. Port-Adapter 패턴 적용 (응답 생성 인터페이스)

추후 SSE나 WebSockets 등으로 스트리밍 구현체를 쉽게 변경할 수 있도록, 도메인 로직과 외부 응답 방식(현재는 Polling)을 분리하는 Port-Adapter 패턴을 적용합니다.

```kotlin
// chat/port/ChatResponsePort.kt
interface ChatResponsePort {
    fun sendPartialResponse(chatId: UUID, partialAnswer: String)
    fun sendCompleteResponse(chatId: UUID, fullAnswer: String)
    fun getIntermediateResponse(chatId: UUID): String?
    fun isCompleted(chatId: UUID): Boolean
}

// chat/adapter/PollingChatResponseAdapter.kt
@Component
class PollingChatResponseAdapter : ChatResponsePort {
    // 임시 캐시(ConcurrentHashMap 등)나 Redis를 활용해 Polling 상태 관리
    // ...
}
```

### 5. Service

```kotlin
// chat/service/ChatService.kt
@Service
class ChatService(
    private val chatRepository: ChatRepository,
    private val threadRepository: ThreadRepository,
    private val aiClient: AiClient, // OpenAI 등과 연동
    private val chatResponsePort: ChatResponsePort
) {
    @Transactional
    fun createChat(userId: UUID, request: ChatCreateRequest): ChatResponse {
        // 1. 유저의 가장 최근 대화(Chat) 조회
        // 2. 가장 최근 대화가 없거나, 생성된 지 30분이 지났으면 새 스레드(Thread) 생성
        // 3. 30분 이내면 기존 스레드 유지
        // 4. 해당 스레드의 이전 대화 내용(Context)을 가져와 AiClient에 전달하여 응답 생성 요청
        // 5. Chat 테이블에 초기 상태 저장 후, 비동기 응답 처리 시작 (스트리밍 시 ChatResponsePort 통해 갱신)
        // 6. 결과 반환 (Polling을 위한 초기 응답)
    }

    @Transactional(readOnly = true)
    fun getChatStatus(chatId: UUID): ChatResponse {
        // Polling 요청을 처리하기 위한 메서드
        // chatResponsePort를 통해 현재 진행 상태 및 부분 답변 반환
    }

    @Transactional(readOnly = true)
    fun getThreads(userId: UUID, role: UserRole, pageable: Pageable): Page<ThreadResponse> {
        // 관리자면 전체 조회 (findAll), 일반 유저면 자신의 쓰레드만 조회 (findAllByUserId)
    }

    @Transactional
    fun deleteThread(userId: UUID, threadId: UUID, role: UserRole) {
        // 본인 소유의 스레드인지 확인
        // 삭제 (연관된 Chat들도 Cascade 또는 직접 삭제 처리)
    }
}
```

### 6. Controller

```kotlin
// chat/controller/ChatController.kt
@RestController
@RequestMapping("/api/chats")
class ChatController(private val chatService: ChatService) {

    @PostMapping
    fun createChat(
        @AuthenticationPrincipal userDetails: CustomUserDetails, // Spring Security 컨텍스트
        @RequestBody request: ChatCreateRequest
    ): ResponseEntity<ApiResponse<ChatResponse>> {
        // ...
    }

    @GetMapping("/{chatId}/status")
    fun getChatStatus(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable chatId: UUID
    ): ResponseEntity<ApiResponse<ChatResponse>> {
        // Polling 방식으로 클라이언트가 상태를 확인하는 엔드포인트
        // ...
    }

    @GetMapping("/threads")
    fun getThreads(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PageableDefault(sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable
    ): ResponseEntity<ApiResponse<Page<ThreadResponse>>> {
        // ...
    }

    @DeleteMapping("/threads/{threadId}")
    fun deleteThread(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable threadId: UUID
    ): ResponseEntity<ApiResponse<Void>> {
        // ...
    }
}
```

---

## 통합 테스트 시나리오

```kotlin
// test: ChatIntegrationTest.kt
@SpringBootTest
@AutoConfigureMockMvc
class ChatIntegrationTest {
    // 1. 인증되지 않은 접근 거부 -> 401
    // 2. 대화 생성: 첫 요청 시 새 스레드 생성 검증 -> 200
    // 3. 대화 생성: 30분 이내 요청 시 기존 스레드 유지 검증 -> 200
    // 4. 폴링 응답 상태 점검: 중간 상태 조회 시 isCompleted=false 확인
    // 5. 권한 분리 검증: 멤버가 남의 스레드 조회/삭제 시도 -> 403 Forbidden 등
    // 6. 관리자 권한 조회: 타인의 스레드 조회 성공 검증
}
```

---

## Git 커밋 가이드

```bash
# Entity & Repository
git add .
git commit -m "feat(chat): add Chat and Thread entities with repositories"

# Adapter (Poling)
git add .
git commit -m "feat(chat): add port-adapter for polling chat response"

# Service & DTOs
git add .
git commit -m "feat(chat): implement chat service with 30-minute thread rule and polling support"

# Controller
git add .
git commit -m "feat(chat): add chat controller with polling status endpoint"

# Tests
git add .
git commit -m "test(chat): add chat and thread integration tests"
```

---

## ✅ 완료 조건

- [ ] 대화 생성 로직 (`30분 기준 스레드 분리/유지`) 구현 완료
- [ ] SSE 대신 Polling 방식을 통한 응답 스트리밍(진행 상태) 조회 구현
- [ ] 데이터 반환 형식을 추후 변경하기 용이하게 Port-Adapter 패턴 적용
- [ ] 대화 목록(`Thread`) 조회 시 페이지네이션 & 권한별 조회 처리 완료
- [ ] 본인 소유 확인 후 스레드 삭제 정상 동작
- [ ] 통합 테스트 시나리오 동작
- [ ] `TASK_STATUS.md` 상태 업데이트
