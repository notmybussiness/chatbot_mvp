const { test, expect } = require("@playwright/test");

test.describe("Chatbot Browser E2E", () => {
  test("회원가입-로그인-채팅-스레드-피드백 플로우", async ({ page }) => {
    const uniqueEmail = `e2e_${Date.now()}@example.com`;
    const password = "password1234";

    await page.goto("/");
    await expect(page.getByRole("heading", { name: /Sionic AI Chatbot MVP/i })).toBeVisible();

    await page.locator("#signup-email").fill(uniqueEmail);
    await page.locator("#signup-password").fill(password);
    await page.locator("#signup-name").fill("E2E User");
    await page.locator("#signup-btn").click();
    await expect(page.locator("#log-output")).toContainText("회원가입 성공");

    await page.locator("#login-email").fill(uniqueEmail);
    await page.locator("#login-password").fill(password);
    await page.locator("#login-btn").click();
    await expect(page.locator("#auth-status")).toContainText("로그인됨");

    await page.locator("#chat-question").fill("브라우저 E2E 테스트 질문입니다.");
    await page.locator("#chat-streaming").setChecked(false);
    await page.locator("#chat-create-btn").click();
    await expect(page.locator("#chat-status")).toContainText("최근 chatId:");
    await expect(page.locator("#chat-answer")).not.toHaveValue("");

    await page.locator("#threads-btn").click();
    await expect(page.locator("#threads-list li")).toHaveCount(1);

    await page.locator("#feedback-positive").setChecked(true);
    await page.locator("#feedback-btn").click();
    await expect(page.locator("#log-output")).toContainText("피드백 저장 성공");
  });

  test("스트리밍 옵션 폴링 완료 확인", async ({ page }) => {
    const uniqueEmail = `stream_${Date.now()}@example.com`;
    const password = "password1234";

    await page.goto("/");

    await page.locator("#signup-email").fill(uniqueEmail);
    await page.locator("#signup-password").fill(password);
    await page.locator("#signup-name").fill("Stream User");
    await page.locator("#signup-btn").click();
    await expect(page.locator("#log-output")).toContainText("회원가입 성공");

    await page.locator("#login-email").fill(uniqueEmail);
    await page.locator("#login-password").fill(password);
    await page.locator("#login-btn").click();
    await expect(page.locator("#auth-status")).toContainText("로그인됨");

    await page.locator("#chat-question").fill("스트리밍 모드로 답변 주세요.");
    await page.locator("#chat-streaming").setChecked(true);
    await page.locator("#chat-create-btn").click();

    await expect(page.locator("#log-output")).toContainText("스트리밍 대화 완료");
    await expect(page.locator("#chat-answer")).not.toHaveValue("");
  });
});
