# Step 2: Common Foundation 구축 (main 브랜치)

## 🎯 목표

`main` 브랜치에 모든 feature 브랜치가 공유할 공통 기반을 구축합니다.
이 단계가 완료되어야 모든 feature 브랜치가 시작할 수 있습니다.

## 📋 참조 문서

- `@TASK_STATUS.md` — 공통 작업 상태 (작업 완료 시 상태 업데이트)
- `@Wanted_Spec/요구사항.md` — 전체 요구사항
- `@Wanted_Spec/spec.md` — 기술 스택 제약
- `@Wanted_Spec/상황.md` — 프로젝트 맥락

## 📌 전제조건

- Kotlin 1.9.x+ / Spring Boot 3.x.x+ (필수)
- Gradle Kotlin DSL
- 단일 모듈 프로젝트

---

## 작업 목록

### 1. 프로젝트 초기화

```bash
# Spring Initializr 또는 수동으로 프로젝트 생성
# 프로젝트 루트: /Users/gyu/Desktop/sionicai/
```

**build.gradle.kts 의존성:**

- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-security`
- `spring-boot-starter-validation`
- `com.h2database:h2` (런타임)
- `org.postgresql:postgresql` (런타임)
- `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson` (0.12.x)
- `com.fasterxml.jackson.module:jackson-module-kotlin`
- `spring-boot-starter-test`
- `spring-boot-starter-webflux` (WebClient용, OpenAI 호출)

### 2. 설정 파일

**application.yml:**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:chatbotdb
    driver-class-name: org.h2.Driver
  h2:
    console:
      enabled: true
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true

jwt:
  secret: ${JWT_SECRET:my-super-secret-key-for-dev-minimum-256-bits-long!!}
  expiration: 86400000 # 24시간

ai:
  provider: mock # mock | openai
  openai:
    api-key: ${OPENAI_API_KEY:}
    model: gpt-3.5-turbo
```

**application-postgres.yml:**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chatbotdb
    driver-class-name: org.postgresql.Driver
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: update
```

### 3. BaseEntity

```kotlin
// common/entity/BaseEntity.kt
@MappedSuperclass
abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    open val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    open val createdAt: OffsetDateTime = OffsetDateTime.now()
}
```

### 4. 공통 DTO

```kotlin
// common/dto/ApiResponse.kt
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val timestamp: OffsetDateTime = OffsetDateTime.now()
) {
    companion object {
        fun <T> success(data: T) = ApiResponse(success = true, data = data)
        fun error(message: String) = ApiResponse<Nothing>(success = false, error = message)
    }
}

// common/dto/PageResponse.kt
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
```

### 5. JWT 인프라

**JwtUtil.kt:**

- `generateToken(userId: UUID, email: String, role: String): String`
- `validateToken(token: String): Boolean`
- `getUserIdFromToken(token: String): UUID`
- `getRoleFromToken(token: String): String`
- JJWT 0.12.x 사용, HS256 알고리즘

**JwtAuthFilter.kt (OncePerRequestFilter):**

- `Authorization: Bearer {token}` 헤더에서 토큰 추출
- 유효한 토큰이면 `UsernamePasswordAuthenticationToken`을 SecurityContext에 세팅
- Principal에 userId, authorities에 role 세팅
- `/api/auth/**` 경로는 필터 스킵

### 6. SecurityConfig

```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfig(private val jwtAuthFilter: JwtAuthFilter) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/auth/**", "/h2-console/**").permitAll()
                  .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .headers { it.frameOptions { fo -> fo.sameOrigin() } } // H2 console
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
```

### 7. AiClient 인터페이스 + Strategy 패턴

```kotlin
// common/client/AiClient.kt (인터페이스)
interface AiClient {
    fun chatCompletion(messages: List<ChatMessage>, model: String? = null): String
}

data class ChatMessage(val role: String, val content: String)

// common/client/MockAiClient.kt
@Component
@Profile("default", "mock")
class MockAiClient : AiClient {
    override fun chatCompletion(messages: List<ChatMessage>, model: String?): String {
        val lastMessage = messages.lastOrNull()?.content ?: "empty"
        return "이것은 Mock 응답입니다. 입력: \"$lastMessage\" (model: ${model ?: "default"})"
    }
}

// common/client/OpenAiClient.kt
@Component
@Profile("openai")
class OpenAiClient(
    @Value("\${ai.openai.api-key}") private val apiKey: String,
    @Value("\${ai.openai.model}") private val defaultModel: String
) : AiClient {
    private val webClient = WebClient.builder()
        .baseUrl("https://api.openai.com/v1")
        .defaultHeader("Authorization", "Bearer $apiKey")
        .build()

    override fun chatCompletion(messages: List<ChatMessage>, model: String?): String {
        // POST /chat/completions 구현
    }
}
```

> ⚠️ 이 Strategy 패턴에 대해 **단위 테스트** 작성 필수!

### 8. GlobalExceptionHandler

```kotlin
@RestControllerAdvice
class GlobalExceptionHandler {
    // IllegalArgumentException → 400
    // AccessDeniedException → 403
    // EntityNotFoundException → 404
    // DuplicateKeyException → 409
    // 기타 → 500
}
```

### 9. data.sql (시드 데이터)

```sql
-- Admin 계정 시드 (BCrypt hash of "admin123")
INSERT INTO users (id, email, password, name, role, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin@example.com',
        '$2a$10$...', 'Admin', 'admin', CURRENT_TIMESTAMP);
```

> ⚠️ BCrypt 해시값은 실행 시 생성하거나 ApplicationRunner에서 삽입

### 10. Git 커밋

```bash
git init
git add .
git commit -m "init: Spring Boot project with common foundation

- Kotlin 1.9.x + Spring Boot 3.x setup
- H2 default DB, Postgres profile
- JWT authentication infrastructure
- AiClient Strategy pattern (Mock + OpenAI)
- Common DTOs, BaseEntity, GlobalExceptionHandler
- Admin seed data"
```

---

## ✅ 완료 조건

- [ ] `./gradlew build` 성공
- [ ] H2 콘솔 접근 가능 (`http://localhost:8080/h2-console`)
- [ ] 인증 없이 `/api/auth/**` 접근 가능, 다른 경로는 401
- [ ] AiClient Strategy 단위 테스트 통과
- [ ] `TASK_STATUS.md`의 Phase 1을 ✅로 업데이트

## ⚡ 완료 후 다음 단계

Phase 1 완료 후, 4개 feature 브랜치를 동시에 생성하고 각 스레드가 작업 시작:

```bash
git checkout -b feature/user-auth
git checkout main && git checkout -b feature/chat
git checkout main && git checkout -b feature/feedback
git checkout main && git checkout -b feature/analytics
```
