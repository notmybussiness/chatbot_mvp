# Step 3a: User Auth Feature (feature/user-auth 브랜치)

## 🎯 목표

사용자 관리 및 인증 기능을 구현합니다. `user/` 패키지만 수정합니다.

## 📋 참조 문서

- `@prompt/TASK_STATUS.md` — 공통 작업 상태 확인
- `@Wanted_Spec/요구사항.md` — "사용자 관리 및 인증 기능" 섹션

## ⚠️ 규칙

- **`common/` 패키지 절대 수정 금지**
- `user/` 패키지만 생성/수정
- Phase 1 (Common Foundation)이 완료된 `main`에서 브랜치 생성

---

## 사전 작업

```bash
git checkout main
git pull
git checkout -b feature/user-auth
```

---

## 구현 상세

### 1. User Entity

```kotlin
// user/entity/User.kt
@Entity
@Table(name = "users")
class User(
    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false)
    val password: String,  // BCrypt 해시

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val role: UserRole = UserRole.MEMBER

) : BaseEntity()

enum class UserRole { MEMBER, ADMIN }
```

### 2. User Repository

```kotlin
// user/repository/UserRepository.kt
interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean
    // Analytics용: 특정 시간 이후 가입한 사용자 수
    fun countByCreatedAtAfter(after: OffsetDateTime): Long
}
```

### 3. DTOs

```kotlin
// user/dto/SignUpRequest.kt
data class SignUpRequest(
    @field:Email val email: String,
    @field:NotBlank val password: String,
    @field:NotBlank val name: String
)

// user/dto/LoginRequest.kt
data class LoginRequest(
    @field:Email val email: String,
    @field:NotBlank val password: String
)

// user/dto/LoginResponse.kt
data class LoginResponse(
    val token: String,
    val userId: UUID,
    val email: String,
    val name: String,
    val role: String
)
```

### 4. UserService

```kotlin
// user/service/UserService.kt
@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil
) {
    fun signUp(request: SignUpRequest): User {
        // 1. 이메일 중복 체크 → 이미 존재하면 예외
        // 2. 비밀번호 BCrypt 인코딩
        // 3. User 엔티티 생성 (role = MEMBER)
        // 4. 저장 후 반환
    }

    fun login(request: LoginRequest): LoginResponse {
        // 1. 이메일로 사용자 조회 → 없으면 예외
        // 2. 비밀번호 검증 → 불일치 시 예외
        // 3. JWT 토큰 생성 (userId, email, role 포함)
        // 4. LoginResponse 반환
    }
}
```

### 5. AuthController

```kotlin
// user/controller/AuthController.kt
@RestController
@RequestMapping("/api/auth")
class AuthController(private val userService: UserService) {

    @PostMapping("/signup")
    fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<ApiResponse<UUID>> {
        // 201 Created 반환
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<ApiResponse<LoginResponse>> {
        // 200 OK + JWT 반환
    }
}
```

---

## 통합 테스트 시나리오

```kotlin
// test: UserAuthIntegrationTest.kt
@SpringBootTest
@AutoConfigureMockMvc
class UserAuthIntegrationTest {
    // 1. 회원가입 성공 → 201
    // 2. 중복 이메일 회원가입 → 409
    // 3. 로그인 성공 → 200 + JWT
    // 4. 잘못된 비밀번호 → 401
    // 5. JWT 토큰으로 인증 보호 API 접근 → 200
    // 6. 토큰 없이 보호 API 접근 → 401
}
```

---

## Git 커밋 가이드

```bash
# Entity & Repository
git add .
git commit -m "feat(user): add User entity and repository"

# Service & DTOs
git add .
git commit -m "feat(user): add signup and login service"

# Controller
git add .
git commit -m "feat(user): add auth controller (signup, login)"

# Tests
git add .
git commit -m "test(user): add auth integration tests"
```

---

## ✅ 완료 조건

- [ ] 회원가입 (`POST /api/auth/signup`) 정상 동작
- [ ] 로그인 (`POST /api/auth/login`) JWT 반환
- [ ] 중복 이메일 가입 시 에러 응답
- [ ] 통합 테스트 통과
- [ ] `TASK_STATUS.md`의 Phase 2a를 ✅로 업데이트
