import { test, expect, Page } from "@playwright/test";
async function login(page: Page, email: string) {
  await page.goto("/");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill("Demo-local-2026!");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Service queue", exact: true }),
  ).toBeVisible();
}
test("employee to agent lifecycle, internal-note privacy, tenant isolation and live updates", async ({
  browser,
}) => {
  const employee = await browser.newPage(),
    agent = await browser.newPage();
  await login(employee, "requester1@demo.local");
  await login(agent, "agent1@demo.local");
  const title = "Browser VPN request " + Date.now();
  await employee.getByRole("button", { name: "+ New ticket" }).click();
  await employee.getByLabel("Title", { exact: true }).fill(title);
  await employee
    .getByLabel("Description", { exact: true })
    .fill("VPN connection needs investigation.");
  await employee
    .getByRole("combobox", { name: "Support team", exact: true })
    .selectOption({ label: "IT Support" });
  await employee
    .getByRole("button", { name: "Submit ticket", exact: true })
    .click();
  await expect(
    employee.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  // No refresh: the second browser must receive the live invalidation.
  await expect(
    agent.getByRole("button").filter({ hasText: title }),
  ).toBeVisible({ timeout: 15000 });
  await agent.getByRole("button").filter({ hasText: title }).click();
  await agent
    .getByRole("combobox", { name: "Change status", exact: true })
    .selectOption("IN_PROGRESS");
  await agent
    .getByLabel("Add a reply", { exact: true })
    .fill("Private diagnostic note");
  await agent.getByLabel("Internal note", { exact: true }).check();
  await agent.getByRole("button", { name: "Post reply", exact: true }).click();
  await expect(
    agent.getByText("Private diagnostic note", { exact: true }),
  ).toBeVisible();
  await expect(
    employee.getByText("Private diagnostic note", { exact: true }),
  ).toHaveCount(0);
  await agent
    .getByLabel("Add a reply", { exact: true })
    .fill("We are investigating your VPN connection.");
  await agent.getByLabel("Internal note", { exact: true }).uncheck();
  await agent.getByRole("button", { name: "Post reply", exact: true }).click();
  await expect(
    employee.getByText("We are investigating your VPN connection.", {
      exact: true,
    }),
  ).toBeVisible({ timeout: 15000 });
  await employee.getByLabel("Attach file", { exact: true }).setInputFiles({
    name: "diagnostics.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("VPN error 42"),
  });
  await employee.getByRole("button", { name: "Upload", exact: true }).click();
  await expect(
    employee.getByRole("link", { name: "diagnostics.txt" }),
  ).toBeVisible();
  await agent
    .getByLabel("Resolution summary (required when resolving)", { exact: true })
    .fill("Reconfigured the VPN profile.");
  await agent
    .getByRole("combobox", { name: "Change status", exact: true })
    .selectOption("RESOLVED");
  await expect(
    employee.getByRole("button", { name: "Confirm resolution" }),
  ).toBeVisible({ timeout: 15000 });
  await employee
    .getByRole("button", { name: "Reopen ticket", exact: true })
    .click();
  await expect(
    employee.getByText("Reconfigured the VPN profile.", { exact: true }),
  ).toBeVisible();
  const me = await (await employee.request.get("/api/me")).json();
  const tenant = me.memberships[0].id;
  const list = await (
    await employee.request.get(
      `/api/tenants/${tenant}/tickets?search=${encodeURIComponent(title)}`,
    )
  ).json();
  const outsider = await browser.newPage();
  await login(outsider, "admin2@demo.local");
  expect(
    (
      await outsider.request.get(`/api/tenants/${tenant}/tickets/${list[0].id}`)
    ).status(),
  ).toBe(404);
  await employee.screenshot({
    path: "test-results/employee-workflow.png",
    fullPage: true,
  });
  await agent.screenshot({
    path: "test-results/agent-workflow.png",
    fullPage: true,
  });
  await employee.close();
  await agent.close();
  await outsider.close();
});
test("administrator policy configuration and mobile layout", async ({
  page,
}) => {
  await login(page, "admin1@demo.local");
  await page
    .getByRole("heading", { name: "SLA policies", exact: true })
    .scrollIntoViewIfNeeded();
  await page.getByLabel("First response (minutes)", { exact: true }).fill("90");
  await page.getByLabel("Resolution (minutes)", { exact: true }).fill("600");
  await page.getByRole("button", { name: "Save policy version" }).click();
  await expect(page.getByText(/response 90 min/).first()).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: "test-results/mobile-admin.png",
    fullPage: true,
  });
});
