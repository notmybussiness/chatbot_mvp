# Step 3c: Feedback Feature (feature/feedback 브랜치)

## 🎯 목표

사용자 피드백 관리 기능을 구현합니다. `feedback/` 패키지만 수정합니다.

## 📋 참조 문서

- `@prompt/TASK_STATUS.md` — 공통 작업 상태 확인
- `@Wanted_Spec/요구사항.md` — "사용자 피드백 관리 기능" 섹션

## ⚠️ 규칙

- **`common/` 패키지 절대 수정 금지**
- `feedback/` 패키지만 생성/수정
- Chat 엔티티를 직접 참조하지 않고 chatId(UUID)로만 참조

---

## 사전 작업

```bash
git checkout main
git pull
git checkout -b feature/feedback
```

---

## 핵심 비즈니스 로직

1. **피드백 생성**: 각 사용자는 하나의 대화에 하나의 피드백만 생성 가능 (userId + chatId 유니크 제약)
2. **권한**: member는 자기 대화에만, admin은 모든 대화에 피드백 생성 가능
3. **상태 변경**: admin만 가능 (pending → resolved)
4. **필터링**: 긍정/부정으로 필터 가능

---

## 구현 상세

### 1. Entity

```kotlin
// feedback/entity/Feedback.kt
@Entity
@Table(
    name = "feedbacks",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "chat_id"])]
)
class Feedback(
    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "chat_id", nullable = false)
    val chatId: UUID,

    @Column(name = "is_positive", nullable = false)
    val isPositive: Boolean,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: FeedbackStatus = FeedbackStatus.PENDING

) : BaseEntity()

enum class FeedbackStatus { PENDING, RESOLVED }
```

### 2. Repository

```kotlin
// feedback/repository/FeedbackRepository.kt
interface FeedbackRepository : JpaRepository<Feedback, UUID> {
    fun existsByUserIdAndChatId(userId: UUID, chatId: UUID): Boolean
    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<Feedback>
    fun findAll(pageable: Pageable): Page<Feedback>

    // 필터링
    fun findAllByUserIdAndIsPositive(userId: UUID, isPositive: Boolean, pageable: Pageable): Page<Feedback>
    fun findAllByIsPositive(isPositive: Boolean, pageable: Pageable): Page<Feedback>
}
```

> 💡 Spring Data JPA Specification 패턴을 사용하면 필터링 조합을 깔끔하게 처리 가능 (Optional)

### 3. DTOs

```kotlin
// feedback/dto/FeedbackRequest.kt
data class CreateFeedbackRequest(
    @field:NotNull val chatId: UUID,
    @field:NotNull val isPositive: Boolean
)

data class UpdateFeedbackStatusRequest(
    @field:NotNull val status: FeedbackStatus
)

// feedback/dto/FeedbackResponse.kt
data class FeedbackResponse(
    val id: UUID,
    val userId: UUID,
    val chatId: UUID,
    val isPositive: Boolean,
    val status: FeedbackStatus,
    val createdAt: OffsetDateTime
)
```

### 4. FeedbackService

```kotlin
// feedback/service/FeedbackService.kt
@Service
class FeedbackService(
    private val feedbackRepository: FeedbackRepository
) {
    fun createFeedback(userId: UUID, role: String, request: CreateFeedbackRequest): FeedbackResponse {
        // 1. 중복 체크: userId + chatId 조합 이미 존재 → 409 Conflict
        // 2. 권한 체크: member는 자기 대화만 (chatId의 소유자 확인)
        //    ⚠️ Chat 테이블이 이 브랜치에 없으므로,
        //    직접 JPQL 또는 네이티브 쿼리로 chat의 user_id 확인
        //    또는 별도의 chatId 검증 로직을 인터페이스로 빼놓기
        // 3. Feedback 저장
    }

    fun listFeedbacks(
        userId: UUID, role: String,
        isPositive: Boolean?, page: Int, size: Int, sortDir: String
    ): PageResponse<FeedbackResponse> {
        // admin: 전체 조회 / member: 자기 것만
        // isPositive 필터 (null이면 전체)
        // 페이지네이션 + 정렬
    }

    fun updateStatus(feedbackId: UUID, request: UpdateFeedbackStatusRequest): FeedbackResponse {
        // 1. 피드백 존재 확인 → 404
        // 2. 상태 업데이트
        // ⚠️ Controller에서 admin 권한 확인 후 호출
    }
}
```

> ⚠️ **Cross-branch 의존성 이슈**: Feedback은 Chat의 user_id를 확인해야 하지만 Chat 엔티티가 이 브랜치에 없습니다.
>
> **해결 방법** (택 1):
>
> 1. `chatId`의 소유자 검증을 Merge 후 통합 테스트에서 확인
> 2. 네이티브 SQL 쿼리로 `chats` 테이블 직접 조회 (테이블이 merge 후에만 존재)
> 3. MVP에서는 소유자 검증 없이 chatId만 저장, 통합 시 검증 추가
>
> **추천: 방법 3** (MVP 우선, merge 후 보완)

### 5. FeedbackController

```kotlin
// feedback/controller/FeedbackController.kt
@RestController
@RequestMapping("/api/feedbacks")
class FeedbackController(private val feedbackService: FeedbackService) {

    @PostMapping
    fun createFeedback(
        @AuthenticationPrincipal userId: UUID,
        // role은 SecurityContext에서 추출
        @Valid @RequestBody request: CreateFeedbackRequest
    ): ResponseEntity<ApiResponse<FeedbackResponse>>  // 201

    @GetMapping
    fun listFeedbacks(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(required = false) isPositive: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "desc") sort: String
    ): ResponseEntity<ApiResponse<PageResponse<FeedbackResponse>>>

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")  // 또는 커스텀 권한 체크
    fun updateStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateFeedbackStatusRequest
    ): ResponseEntity<ApiResponse<FeedbackResponse>>
}
```

---

## 통합 테스트 시나리오

```kotlin
// test: FeedbackIntegrationTest.kt
@SpringBootTest
@AutoConfigureMockMvc
class FeedbackIntegrationTest {
    // 1. 피드백 생성 성공 → 201
    // 2. 같은 chatId에 중복 피드백 → 409
    // 3. 피드백 목록 조회 (member → 자기 것만)
    // 4. 피드백 목록 조회 (admin → 전체)
    // 5. isPositive 필터링 정상 동작
    // 6. 페이지네이션 + 정렬 확인
    // 7. admin 피드백 상태 변경 → 200
    // 8. member 피드백 상태 변경 시도 → 403
}
```

---

## Git 커밋 가이드

```bash
git commit -m "feat(feedback): add Feedback entity and repository"
git commit -m "feat(feedback): implement feedback service with validation"
git commit -m "feat(feedback): add feedback controller"
git commit -m "test(feedback): add feedback integration tests"
```

---

## ✅ 완료 조건

- [ ] 피드백 생성 (`POST /api/feedbacks`) + 중복 방지
- [ ] 피드백 목록 조회 (권한별, 필터, 페이지네이션)
- [ ] 상태 변경 (`PATCH /api/feedbacks/{id}/status`) admin 전용
- [ ] 통합 테스트 통과
- [ ] `TASK_STATUS.md`의 Phase 2c를 ✅로 업데이트
