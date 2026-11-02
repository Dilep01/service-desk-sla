import { test, expect, Page } from "@playwright/test";
async function signIn(page: Page, email: string, password: string) {
  await page.goto("/");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Service queue", exact: true }),
  ).toBeVisible();
}
async function code(page: Page, email: string, subject: string) {
  let value = "";
  await expect
    .poll(
      async () => {
        const inbox = await (
          await page.request.get(
            (process.env.MAILPIT_URL || "http://127.0.0.1:18025") +
              "/api/v1/messages",
          )
        ).json();
        const message = inbox.messages.find(
          (m: any) =>
            m.Subject === subject && m.To.some((r: any) => r.Address === email),
        );
        if (!message) return false;
        const body = await (
          await page.request.get(
            (process.env.MAILPIT_URL || "http://127.0.0.1:18025") +
              "/api/v1/message/" +
              message.ID,
          )
        ).json();
        value = body.Text.trim().split(" ").at(-1);
        return !!value;
      },
      { timeout: 20000 },
    )
    .toBeTruthy();
  return value;
}
test("invitation and password reset deliver locally and revoke an existing session", async ({
  browser,
}) => {
  const admin = await browser.newPage(),
    person = await browser.newPage();
  await signIn(admin, "admin1@demo.local", "Demo-local-2026!");
  const email = `employee-${Date.now()}@demo.local`;
  await admin.getByLabel("Invite email", { exact: true }).fill(email);
  await admin
    .getByRole("button", { name: "Send invitation", exact: true })
    .click();
  const invitation = await code(admin, email, "Service Desk invitation");
  await person.goto("/");
  await person.getByText("Accept an invitation", { exact: true }).click();
  await person.getByLabel("Invitation code", { exact: true }).fill(invitation);
  await person
    .getByLabel("Display name", { exact: true })
    .fill("Invited Employee");
  await person
    .getByLabel("Password for a new account", { exact: true })
    .fill("Initial-test-password!");
  await person
    .getByRole("button", { name: "Accept invitation", exact: true })
    .click();
  await expect(person.getByRole("status")).toContainText("completed");
  await signIn(person, email, "Initial-test-password!");
  const reset = await browser.newPage();
  await reset.goto("/");
  await reset.getByText("Reset password", { exact: true }).first().click();
  await reset.getByLabel("Account email", { exact: true }).fill(email);
  await reset.getByRole("button", { name: "Request reset code" }).click();
  const token = await code(reset, email, "Service Desk password reset");
  await reset.getByLabel("Reset code", { exact: true }).fill(token);
  await reset
    .getByLabel("New password", { exact: true })
    .fill("Changed-test-password!");
  await reset
    .getByRole("button", { name: "Reset password", exact: true })
    .click();
  await expect(reset.getByRole("status")).toContainText("completed");
  expect((await person.request.get("/api/me")).status()).toBe(401);
  await signIn(reset, email, "Changed-test-password!");
  await admin.close();
  await person.close();
  await reset.close();
});
test("cross-tenant WebSocket cannot receive updates", async ({ page }) => {
  await signIn(page, "admin2@demo.local", "Demo-local-2026!");
  const outcome = await page.evaluate(
    () =>
      new Promise<string>((resolve) => {
        const ws = new WebSocket(
          "ws://" +
            location.host +
            "/api/live?tenant=00000000-0000-0000-0000-000000000000",
        );
        ws.onmessage = () => {
          resolve("leaked");
          ws.close();
        };
        ws.onclose = () => resolve("closed");
        setTimeout(() => {
          resolve("timeout");
          ws.close();
        }, 5000);
      }),
  );
  expect(outcome).toBe("closed");
});
