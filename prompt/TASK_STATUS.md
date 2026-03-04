# 🔄 AI Chatbot MVP — 공통 작업 상태

> **이 파일은 모든 스레드가 참조하는 공유 상태 파일입니다.**

## 전체 진행 상황

| Phase                        | 상태                           | 담당        |
| ---------------------------- | ------------------------------ | ----------- |
| Phase 1: Common Foundation   | ✅ 완료                        | 공통 스레드 |
| Phase 2a: User Auth          | ⬜ 대기 (시작 가능)            | 스레드 A    |
| Phase 2b: Chat               | ⬜ 대기 (시작 가능)            | 스레드 B    |
| Phase 2c: Feedback           | ⬜ 대기 (시작 가능)            | 스레드 C    |
| Phase 2d: Analytics          | ⬜ 대기 (시작 가능)            | 스레드 D    |
| Phase 3: Merge & Integration | ⬜ 대기 (Phase 2 전체 완료 후) | 통합 스레드 |

## 상태 아이콘

- ⬜ 대기 | 🔄 진행중 | ✅ 완료 | ❌ 블로킹

---

## 확정된 공통 규칙

### 패키지 구조

```
com.sionicai.chatbot
├── common/     ← 공통 (main 브랜치, 수정 금지!)
├── user/       ← feature/user-auth만 수정
├── chat/       ← feature/chat만 수정
├── feedback/   ← feature/feedback만 수정
└── analytics/  ← feature/analytics만 수정
```

### Git 규칙

1. `main` 브랜치의 `common/` 패키지는 **어떤 feature 브랜치도 수정 불가**
2. 각 feature 브랜치는 **자신의 패키지만** 생성/수정
3. Merge 순서: `user-auth` → `chat` → `feedback` → `analytics`

### 공통 인터페이스 사용법

```kotlin
// JWT 인증된 요청에서 사용자 정보 추출
val userId = SecurityUtils.getCurrentUserId()   // UUID
val role = SecurityUtils.getCurrentUserRole()     // "member" | "admin"
val isAdmin = SecurityUtils.isAdmin()             // Boolean

// AI 클라이언트 주입 (Strategy 패턴)
@Service
class MyService(private val aiClient: AiClient) {
    fun ask(question: String): String {
        val messages = listOf(ChatMessage("user", question))
        return aiClient.chatCompletion(messages, model = null)
    }
}

// 공통 응답 포맷
ApiResponse.success(data)    // { success: true, data: ... }
ApiResponse.error("message") // { success: false, error: "..." }

// 페이지네이션
PageResponse.of(content, page, size, totalElements)

// 커스텀 예외 (GlobalExceptionHandler가 자동 처리)
throw DuplicateResourceException("User", "email", "test@test.com") // 409
throw ResourceNotFoundException("Chat", chatId)                     // 404
throw UnauthorizedException("Invalid credentials")                  // 401
throw ForbiddenException("Admin only")                              // 403
```

### Spring Security 설정

- `/api/auth/**` → 인증 불필요
- 그 외 전부 → JWT 필수
- `@PreAuthorize("hasRole('ADMIN')")` → admin 전용

### 테스트 규칙

- **단위 테스트**: AiClient Strategy 패턴만 (완료)
- **통합 테스트**: 각 기능별 `@SpringBootTest` + `MockMvc` + H2
