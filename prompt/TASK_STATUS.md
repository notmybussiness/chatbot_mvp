# 🔄 AI Chatbot MVP — 공통 작업 상태

> **이 파일은 모든 스레드가 참조하는 공유 상태 파일입니다.**
> 각 스레드는 자신의 작업 상태를 업데이트하고, 다른 스레드의 상태를 확인합니다.

## 전체 진행 상황

| Phase                        | 상태                           | 담당        |
| ---------------------------- | ------------------------------ | ----------- |
| Phase 1: Common Foundation   | ⬜ 대기                        | 공통 스레드 |
| Phase 2a: User Auth          | ⬜ 대기 (Phase 1 완료 후 시작) | 스레드 A    |
| Phase 2b: Chat               | ⬜ 대기 (Phase 1 완료 후 시작) | 스레드 B    |
| Phase 2c: Feedback           | ⬜ 대기 (Phase 1 완료 후 시작) | 스레드 C    |
| Phase 2d: Analytics          | ⬜ 대기 (Phase 1 완료 후 시작) | 스레드 D    |
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

### 공통 인터페이스 (Common에서 제공)

```kotlin
// --- AiClient 인터페이스 ---
interface AiClient {
    fun chatCompletion(messages: List<ChatMessage>, model: String): String
}

// --- BaseEntity ---
@MappedSuperclass
abstract class BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID()

    @Column(updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
}

// --- ApiResponse ---
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
)

// --- JWT에서 추출되는 사용자 정보 ---
// SecurityContext에서 userId, role 추출 가능
// JwtAuthFilter가 Authentication 객체에 userId, role 세팅
```

### 테스트 규칙

- **단위 테스트**: AiClient Strategy 패턴만
- **통합 테스트**: 각 기능별 MockMvc + H2
- **E2E**: Playwright (CI에서 Postgres)
