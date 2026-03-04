# Step 3d: Analytics Feature (feature/analytics 브랜치)

## 🎯 목표

분석 및 보고 기능을 구현합니다. `analytics/` 패키지만 수정합니다.

## 📋 참조 문서

- `@prompt/TASK_STATUS.md` — 공통 작업 상태 확인
- `@Wanted_Spec/요구사항.md` — "분석 및 보고 기능" 섹션

## ⚠️ 규칙

- **`common/` 패키지 절대 수정 금지**
- `analytics/` 패키지만 생성/수정
- Admin 전용 기능 — 모든 엔드포인트에 admin 권한 체크

---

## 사전 작업

```bash
git checkout main
git pull
git checkout -b feature/analytics
```

---

## 핵심 비즈니스 로직

1. **사용자 활동 기록**: 요청 시점으로부터 **하루(24시간) 동안**의 회원가입 수, 로그인 수, 대화 생성 수
2. **CSV 보고서**: 요청 시점으로부터 하루 동안의 모든 사용자 대화 목록 + 생성한 사용자 정보

---

## 구현 상세

### 1. DTOs

```kotlin
// analytics/dto/ActivityResponse.kt
data class ActivityResponse(
    val signUpCount: Long,
    val loginCount: Long,
    val chatCount: Long,
    val periodStart: OffsetDateTime,  // 24시간 전
    val periodEnd: OffsetDateTime     // 현재
)
```

### 2. 로그인 기록 문제 해결

> ⚠️ **이슈**: 요구사항에 "로그인 수"가 있지만, User 테이블에는 로그인 기록이 없음.
>
> **해결 방법 (택 1)**:
>
> 1. `LoginLog` 엔티티를 analytics/ 패키지에 생성 → user-auth 브랜치의 UserService에서 기록
>    → ❌ 다른 브랜치 수정 필요
> 2. `LoginLog` 테이블만 analytics/에 만들고, merge 후 UserService에 기록 로직 추가
>    → ✅ 추천
> 3. User의 `lastLoginAt` 필드 추가 → 직접 카운트 불가
>    → ❌ 부정확
>
> **추천: 방법 2** — LoginLog 엔티티를 만들어두고, merge 후 통합 단계에서 UserService에 로그인 시 기록 로직 추가

```kotlin
// analytics/entity/LoginLog.kt
@Entity
@Table(name = "login_logs")
class LoginLog(
    @Column(name = "user_id", nullable = false)
    val userId: UUID
) : BaseEntity()

// analytics/repository/LoginLogRepository.kt
interface LoginLogRepository : JpaRepository<LoginLog, UUID> {
    fun countByCreatedAtAfter(after: OffsetDateTime): Long
}
```

### 3. AnalyticsService

```kotlin
// analytics/service/AnalyticsService.kt
@Service
class AnalyticsService(
    private val loginLogRepository: LoginLogRepository
    // ⚠️ UserRepository, ChatRepository는 다른 브랜치 소유
    // merge 후에 주입받아 사용
    // MVP에서는 네이티브 쿼리로 직접 카운트
) {
    fun getActivity(): ActivityResponse {
        val since = OffsetDateTime.now().minusHours(24)
        val now = OffsetDateTime.now()

        // 네이티브 쿼리로 카운트 (다른 브랜치 엔티티에 의존하지 않기 위해)
        val signUpCount = executeCountQuery("SELECT COUNT(*) FROM users WHERE created_at > ?", since)
        val loginCount = loginLogRepository.countByCreatedAtAfter(since)
        val chatCount = executeCountQuery("SELECT COUNT(*) FROM chats WHERE created_at > ?", since)

        return ActivityResponse(signUpCount, loginCount, chatCount, since, now)
    }

    fun generateReport(): ByteArray {
        val since = OffsetDateTime.now().minusHours(24)

        // 네이티브 쿼리로 데이터 조회
        val results = executeReportQuery("""
            SELECT c.id, c.question, c.answer, c.created_at, u.email, u.name
            FROM chats c JOIN users u ON c.user_id = u.id
            WHERE c.created_at > ?
            ORDER BY c.created_at ASC
        """, since)

        // CSV 생성
        return buildCsvReport(results)
    }

    private fun buildCsvReport(results: List<Map<String, Any>>): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("chat_id,question,answer,created_at,user_email,user_name")
        results.forEach { row ->
            sb.appendLine("${row["id"]},\"${escape(row["question"])}\",\"${escape(row["answer"])}\",${row["created_at"]},${row["user_email"]},${row["user_name"]}")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
```

> 💡 **EntityManager를 이용한 네이티브 쿼리** 패턴을 사용하여 다른 브랜치 엔티티 의존 없이 구현

### 4. AnalyticsController

```kotlin
// analytics/controller/AnalyticsController.kt
@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(private val analyticsService: AnalyticsService) {

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
            .header("Content-Disposition", "attachment; filename=report_${LocalDate.now()}.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv)
    }
}
```

---

## 통합 테스트 시나리오

```kotlin
// test: AnalyticsIntegrationTest.kt
@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsIntegrationTest {
    // 1. admin 활동 기록 요청 → 200 + 카운트 값
    // 2. member 활동 기록 요청 → 403
    // 3. admin CSV 보고서 다운로드 → 200 + CSV 컨텐츠
    // 4. member CSV 보고서 요청 → 403
    // 5. CSV에 올바른 헤더와 데이터 포함 확인
}
```

> ⚠️ 통합 테스트 시 `users`, `chats` 테이블이 존재해야 함.
> merge 전에는 네이티브 쿼리가 테이블 미존재로 실패할 수 있으므로,
> 테스트용 `schema.sql`에 최소한의 테이블 스키마를 포함하거나 `@Sql` 어노테이션 사용

---

## Git 커밋 가이드

```bash
git commit -m "feat(analytics): add LoginLog entity for tracking"
git commit -m "feat(analytics): implement activity stats service"
git commit -m "feat(analytics): add CSV report generation"
git commit -m "feat(analytics): add analytics controller (admin-only)"
git commit -m "test(analytics): add analytics integration tests"
```

---

## ✅ 완료 조건

- [ ] 활동 기록 API (`GET /api/analytics/activity`) admin 전용
- [ ] CSV 보고서 다운로드 (`GET /api/analytics/report`)
- [ ] member 접근 시 403
- [ ] 통합 테스트 통과 (또는 merge 후 통과)
- [ ] `TASK_STATUS.md`의 Phase 2d를 ✅로 업데이트
