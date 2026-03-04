# Step 4: Merge & Integration (통합 단계)

## 🎯 목표

모든 feature 브랜치를 `main`에 merge하고, 크로스 브랜치 의존성을 해결합니다.

## 📋 참조 문서

- `@prompt/TASK_STATUS.md` — 모든 Phase 2 완료 확인 후 진행

## ⚠️ 전제조건

- Phase 2a (user-auth) ✅
- Phase 2b (chat) ✅
- Phase 2c (feedback) ✅
- Phase 2d (analytics) ✅

---

## Merge 순서 (의존성 기반)

```bash
# 1. user-auth 먼저 (다른 기능의 User 참조 기반)
git checkout main
git merge feature/user-auth --no-ff -m "merge: feature/user-auth into main"

# 2. chat (Thread, Chat 엔티티 추가)
git merge feature/chat --no-ff -m "merge: feature/chat into main"

# 3. feedback (Chat 존재 후 merge)
git merge feature/feedback --no-ff -m "merge: feature/feedback into main"

# 4. analytics (모든 테이블 존재 후 merge)
git merge feature/analytics --no-ff -m "merge: feature/analytics into main"
```

---

## 통합 후 보완 작업

### 1. Feedback → Chat 소유자 검증 추가

```kotlin
// feedback/service/FeedbackService.kt 수정
// ChatRepository를 주입받아 chatId의 소유자 확인
```

### 2. UserService → LoginLog 기록 추가

```kotlin
// user/service/UserService.kt의 login() 메서드에서
// loginLogRepository.save(LoginLog(user.id)) 호출
```

### 3. 전체 통합 테스트 실행

```bash
./gradlew test
```

### 4. E2E 시나리오 테스트 (Playwright)

```
1. 회원가입 → 로그인
2. 대화 생성 (여러 번) → 스레드 확인
3. 피드백 생성
4. admin 로그인 → 활동 기록 조회 → CSV 다운로드
```

### 5. README.md 작성

- 프로젝트 개요, 실행 방법, API 목록, 과제 분석 문서

---

## Git 커밋 (통합 후)

```bash
git commit -m "fix: add cross-branch dependencies after merge

- FeedbackService: add chat ownership validation
- UserService: add LoginLog recording on login"

git commit -m "docs: add README with API manual and analysis"
```

## ✅ 완료 조건

- [ ] 모든 브랜치 merge 완료
- [ ] `./gradlew test` 전체 통과
- [ ] 크로스 의존성 해결 (Feedback↔Chat, Analytics↔User)
- [ ] README.md 작성 완료
- [ ] `TASK_STATUS.md` 전체 ✅
