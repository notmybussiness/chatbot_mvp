# Step 3d: Analytics Feature (feature/analytics 브랜치)

## 🎯 목표

관리자를 위한 분석 및 보고 기능을 구현합니다. 도메인 간 결합도를 낮추기 위해 Adapter 패턴을 사용하여 User 및 Chat 도메인의 데이터를 취합합니다.

## 📋 참조 문서

- `@prompt/TASK_STATUS.md` — 공통 작업 상태 확인
- `@Wanted_Spec/요구사항.md` — "분석 및 보고 기능" 섹션

## ⚠️ 규칙

- `analytics/` 패키지를 중심으로 작업하되, 타 도메인(User, Chat) 데이터 조회를 위해 Adapter 패턴을 활용합니다.
- Admin 전용 기능이므로 모든 엔드포인트에 인증 및 권한(`ADMIN`) 체크를 확실히 적용합니다.

---

## 사전 작업

```bash
git checkout main
git pull
git checkout -b feature/analytics
```

---

## 핵심 비즈니스 로직

1. **사용자 활동 기록**: 요청 시점으로부터 **하루(24시간) 동안**의 회원가입 수, 로그인 수, 대화 생성 수 조회
2. **CSV 보고서**: 요청 시점으로부터 하루 동안의 모든 사용자 대화 목록 + 해당 대화를 생성한 사용자 정보 다운로드

---

## 구현 상세

### 1. DTOs

```kotlin
// analytics/dto/ActivityResponse.kt
data class ActivityResponse(
    val signUpCount: Long,
    val loginCount: Long,
    val chatCount: Long,
    val periodStart: OffsetDateTime,
    val periodEnd: OffsetDateTime
)
```

### 2. 로그인 기록 (LoginLog)

요구사항의 "로그인 수"를 집계하기 위해 Analytics 도메인 내부에 `LoginLog` 엔티티를 생성합니다. 나중에 `user-auth` 관련 로직과 병합(merge)할 때 로그인 성공 시 해당 기록을 남기도록 연동할 예정입니다.

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

### 3. Analytics Adapter (도메인 종합)

Analytics 도메인이 User나 Chat 모듈의 내부 구현에 강하게 결합되지 않도록 **Adapter 역할**을 하는 컴포넌트를 만듭니다. 스프링의 특성상 각 도메인의 Repository를 직접 주입받아 데이터를 가공하는 역할을 이 Adapter가 수행합니다. (필요 시 각 도메인의 Service나 별도 포트를 참조할 수도 있습니다.)

```kotlin
// analytics/adapter/AnalyticsDataAdapter.kt
@Component
class AnalyticsDataAdapter(
    private val userRepository: UserRepository,       // user-auth 브랜치에서 생성될 예정
    private val chatRepository: ChatRepository,       // chat 브랜치에서 생성될 예정
    private val loginLogRepository: LoginLogRepository
) {
    fun getSignUpCountSince(since: OffsetDateTime): Long {
        return userRepository.countByCreatedAtAfter(since)
    }

    fun getLoginCountSince(since: OffsetDateTime): Long {
        return loginLogRepository.countByCreatedAtAfter(since)
    }

    fun getChatCountSince(since: OffsetDateTime): Long {
        return chatRepository.countByCreatedAtAfter(since)
    }

    // CSV 보고서를 위한 데이터 취합
    fun getChatReportDataSince(since: OffsetDateTime): List<ChatReportDto> {
        // Chat과 연관된 Thread, 그리고 Thread의 Owner(User) 정보를 패치 조인 등으로 가져오는 로직.
        // JPA DTO Projection이나 JPQL을 활용하여 조회합니다.
        // 예시 시그니처입니다.
        return chatRepository.findChatReportDataAfter(since)
    }
}
```

_(참고: `UserRepository`나 `ChatRepository`가 아직 현재 브랜치에 없다면, 타 도메인의 엔티티 및 리포지토리가 있다고 가정하고 코드를 작성하거나, 병합 후 의존성을 연결합니다.)_

### 4. AnalyticsService

어댑터를 통해 취합된 데이터를 바탕으로 비즈니스 로직(CSV 생성 등)을 처리합니다.

```kotlin
// analytics/service/AnalyticsService.kt
@Service
class AnalyticsService(
    private val analyticsDataAdapter: AnalyticsDataAdapter
) {
    fun getActivity(): ActivityResponse {
        val now = OffsetDateTime.now()
        val since = now.minusHours(24)

        val signUpCount = analyticsDataAdapter.getSignUpCountSince(since)
        val loginCount = analyticsDataAdapter.getLoginCountSince(since)
        val chatCount = analyticsDataAdapter.getChatCountSince(since)

        return ActivityResponse(signUpCount, loginCount, chatCount, since, now)
    }

    fun generateReport(): ByteArray {
        val since = OffsetDateTime.now().minusHours(24)
        val reportData = analyticsDataAdapter.getChatReportDataSince(since)

        return buildCsvReport(reportData)
    }

    private fun buildCsvReport(results: List<ChatReportDto>): ByteArray {
        val sb = StringBuilder()
        // CSV 헤더 (BOM 추가 권장: 한글 깨짐 방지)
        sb.appendLine("chat_id,question,answer,created_at,user_email,user_name")
        results.forEach { row ->
            // 필드 내 따옴표나 쉼표 처리 로직 유의
            sb.appendLine("${row.chatId},\"${escape(row.question)}\",\"${escape(row.answer)}\",${row.createdAt},${row.userEmail},${row.userName}")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // CSV 이스케이프 유틸 함수
    private fun escape(value: String): String {
        return value.replace("\"", "\"\"")
    }
}
```

### 5. AnalyticsController

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
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report_${LocalDate.now()}.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv)
    }
}
```

---

## 통합 테스트 시나리오

1. **Admin 활동 기록 요청**: 어댑터를 Mocking 하거나 실제 DB에 데이터를 넣은 뒤 `GET /api/analytics/activity` 호출 -> 상태 코드 200 및 카운트 검증
2. **Member 권한 요청**: 일반 유저 토큰으로 예외 발생(403) 확인
3. **CSV 다운로드**: 200 OK와 올바른 CSV 포맷/헤더 검증

> 💡 **Tip:** 아직 타 도메인 리포지토리가 미완성이므로, Adapter를 Mocking(`@MockBean` 등) 하여 Analytics 도메인만의 단위/통합 테스트를 우선 작성하는 방식을 권장합니다.

---

## Git 커밋 가이드

```bash
git commit -m "feat(analytics): add LoginLog entity and repository"
git commit -m "feat(analytics): implement AnalyticsDataAdapter for cross-domain data"
git commit -m "feat(analytics): implement analytics service and CSV generation"
git commit -m "feat(analytics): add analytics controller with admin restrictions"
git commit -m "test(analytics): add tests with mocked adapter"
```

---

## ✅ 완료 조건

- [ ] `LoginLog` 엔티티 및 Repository 등 로그인 기록 기반 구조 구상
- [ ] `AnalyticsDataAdapter` 구조 확립 및 적용
- [ ] 활동 기록 API (`GET /api/analytics/activity`) 구현 (Admin 전용)
- [ ] CSV 보고서 다운로드 (`GET /api/analytics/report`) 구현 (Admin 전용)
- [ ] 접근 제한(Member 403) 테스트 작성
- [ ] `TASK_STATUS.md`의 Phase 2d 진행 상태 업데이트
