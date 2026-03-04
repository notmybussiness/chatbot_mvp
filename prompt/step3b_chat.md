# Step 3b: Chat Feature (feature/chat 브랜치)

## 🎯 목표

대화(Chat) 관리 기능을 구현합니다. `chat/` 패키지만 수정합니다.
핵심은 **30분 스레드 유지 규칙**과 **AiClient(Mock) 연동**입니다.

## 📋 참조 문서

- `@prompt/TASK_STATUS.md` — 공통 작업 상태 확인
- `@Wanted_Spec/요구사항.md` — "대화(chat) 관리 기능" 섹션

## ⚠️ 규칙

- **`common/` 패키지 절대 수정 금지**
- `chat/` 패키지만 생성/수정
- `AiClient` 인터페이스는 `common/client/`에서 주입받아 사용

---

## 사전 작업

```bash
git checkout main
git pull
git checkout -b feature/chat
```

---

## 핵심 비즈니스 로직: 30분 스레드 규칙

```
1. 유저의 마지막 대화 시간 조회
2. IF 대화 이력 없음 OR 마지막 대화 후 30분 초과:
   → 새 스레드 생성
3. ELSE:
   → 기존 스레드 유지
4. 해당 스레드의 모든 대화를 messages로 구성
5. AiClient.chatCompletion(messages) 호출
6. 질문 + 답변 저장
```

---

## 구현 상세

### 1. Entities

```kotlin
// chat/entity/ChatThread.kt
@Entity
@Table(name = "chat_threads")
class ChatThread(
    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "last_chat_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    var lastChatAt: OffsetDateTime = OffsetDateTime.now()

) : BaseEntity()

// chat/entity/Chat.kt
@Entity
@Table(name = "chats")
class Chat(
    @Column(name = "thread_id", nullable = false)
    val threadId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(nullable = false, columnDefinition = "TEXT")
    val question: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val answer: String

) : BaseEntity()
```

### 2. Repositories

```kotlin
// chat/repository/ChatThreadRepository.kt
interface ChatThreadRepository : JpaRepository<ChatThread, UUID> {
    fun findFirstByUserIdOrderByLastChatAtDesc(userId: UUID): ChatThread?
    fun findAllByUserId(userId: UUID): List<ChatThread>
}

// chat/repository/ChatRepository.kt
interface ChatRepository : JpaRepository<Chat, UUID> {
    fun findAllByThreadIdOrderByCreatedAtAsc(threadId: UUID): List<Chat>
    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID, pageable: Pageable): Page<Chat>
    // Analytics용
    fun countByCreatedAtAfter(after: OffsetDateTime): Long
}
```

### 3. DTOs

```kotlin
// chat/dto/ChatRequest.kt
data class ChatRequest(
    @field:NotBlank val question: String,
    val model: String? = null  // 선택: 특정 모델 지정
)

// chat/dto/ChatResponse.kt
data class ChatResponse(
    val id: UUID,
    val threadId: UUID,
    val question: String,
    val answer: String,
    val createdAt: OffsetDateTime
)

// chat/dto/ThreadResponse.kt
data class ThreadResponse(
    val threadId: UUID,
    val createdAt: OffsetDateTime,
    val lastChatAt: OffsetDateTime,
    val chats: List<ChatResponse>
)
```

### 4. ChatService

```kotlin
// chat/service/ChatService.kt
@Service
class ChatService(
    private val chatThreadRepository: ChatThreadRepository,
    private val chatRepository: ChatRepository,
    private val aiClient: AiClient  // 공통 인터페이스 주입
) {
    companion object {
        const val THREAD_TIMEOUT_MINUTES = 30L
    }

    @Transactional
    fun createChat(userId: UUID, request: ChatRequest): ChatResponse {
        // 1. 현재 활성 스레드 결정
        val thread = resolveThread(userId)

        // 2. 기존 대화 이력 조회 → ChatMessage 리스트 구성
        val history = chatRepository.findAllByThreadIdOrderByCreatedAtAsc(thread.id)
        val messages = history.flatMap {
            listOf(ChatMessage("user", it.question), ChatMessage("assistant", it.answer))
        } + ChatMessage("user", request.question)

        // 3. AI 호출 (Mock 또는 OpenAI)
        val answer = aiClient.chatCompletion(messages, request.model)

        // 4. Chat 저장
        val chat = chatRepository.save(Chat(thread.id, userId, request.question, answer))

        // 5. 스레드 lastChatAt 갱신
        thread.lastChatAt = OffsetDateTime.now()
        chatThreadRepository.save(thread)

        return chat.toResponse()
    }

    private fun resolveThread(userId: UUID): ChatThread {
        val latest = chatThreadRepository.findFirstByUserIdOrderByLastChatAtDesc(userId)
        return if (latest == null ||
            latest.lastChatAt.plusMinutes(THREAD_TIMEOUT_MINUTES).isBefore(OffsetDateTime.now())) {
            // 새 스레드 생성
            chatThreadRepository.save(ChatThread(userId))
        } else {
            latest
        }
    }

    fun listChats(userId: UUID, role: String, page: Int, size: Int, sortDir: String): PageResponse<ThreadResponse> {
        // admin이면 전체, member이면 자기 것만
        // 스레드 단위 그룹화
        // 페이지네이션 + 정렬 (createdAt 기준)
    }

    @Transactional
    fun deleteThread(userId: UUID, role: String, threadId: UUID) {
        // 스레드 존재 확인
        // admin이 아니면 소유자 확인
        // 해당 스레드의 모든 Chat 삭제
        // 스레드 삭제
    }
}
```

### 5. ChatController

```kotlin
// chat/controller/ChatController.kt
@RestController
@RequestMapping("/api")
class ChatController(private val chatService: ChatService) {

    @PostMapping("/chats")
    fun createChat(
        @AuthenticationPrincipal userId: UUID,  // JWT에서 추출
        @Valid @RequestBody request: ChatRequest
    ): ResponseEntity<ApiResponse<ChatResponse>>

    @GetMapping("/chats")
    fun listChats(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "desc") sort: String  // asc | desc
    ): ResponseEntity<ApiResponse<PageResponse<ThreadResponse>>>

    @DeleteMapping("/threads/{threadId}")
    fun deleteThread(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable threadId: UUID
    ): ResponseEntity<ApiResponse<Unit>>
}
```

> ⚠️ `@AuthenticationPrincipal`에서 userId를 추출하는 방법은 Common의 JwtAuthFilter 구현에 따라 다를 수 있음. Custom ArgumentResolver가 필요할 수 있음.

---

## 통합 테스트 시나리오

```kotlin
// test: ChatIntegrationTest.kt
@SpringBootTest
@AutoConfigureMockMvc
class ChatIntegrationTest {
    // 1. 첫 대화 생성 → 새 스레드 자동 생성 확인
    // 2. 30분 이내 두 번째 대화 → 같은 스레드 유지 확인
    // 3. 30분 이후 대화 → 새 스레드 생성 확인 (시간 조작 필요)
    // 4. 대화 목록 조회 → 스레드별 그룹화 확인
    // 5. admin은 전체 조회 가능
    // 6. member는 자기 것만 조회
    // 7. 스레드 삭제 → 소유자만 가능
    // 8. AI 응답이 Mock 응답과 일치하는지 확인
}
```

### 30분 규칙 테스트 팁

```kotlin
// 시간 관련 테스트를 위해 Clock을 주입하거나,
// 직접 thread.lastChatAt을 과거로 설정하여 테스트
val thread = chatThreadRepository.findById(threadId).get()
thread.lastChatAt = OffsetDateTime.now().minusMinutes(31)
chatThreadRepository.save(thread)
```

---

## Git 커밋 가이드

```bash
git add .
git commit -m "feat(chat): add Thread and Chat entities and repositories"

git add .
git commit -m "feat(chat): implement ChatService with 30-min thread rule"

git add .
git commit -m "feat(chat): add ChatController with CRUD endpoints"

git add .
git commit -m "test(chat): add chat integration tests with thread rule verification"
```

---

## ✅ 완료 조건

- [ ] 대화 생성 (`POST /api/chats`) Mock AI 응답 정상
- [ ] 30분 스레드 규칙 동작 검증
- [ ] 대화 목록 스레드별 그룹화 + 페이지네이션
- [ ] 스레드 삭제 (소유자 권한 검증)
- [ ] 통합 테스트 통과
- [ ] `TASK_STATUS.md`의 Phase 2b를 ✅로 업데이트
