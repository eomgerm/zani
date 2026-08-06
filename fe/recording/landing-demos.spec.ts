import { mkdir, rm } from "node:fs/promises";
import path from "node:path";
import { expect, test, type Locator, type Page, type Route } from "@playwright/test";

const videoSize = { width: 1280, height: 800 };
const tempVideoDir = path.resolve("recordings", ".video");
const assetDir = path.resolve("public", "asset");
const demoSessionId = "9000000000000001";

type DemoRole = "INSTRUCTOR" | "STUDENT";

type DemoScenario = {
  path: string;
  output: string;
  role?: DemoRole;
  ready: (page: Page) => Locator;
  run: (page: Page) => Promise<void>;
};

const envelope = (data: unknown) => ({ isSuccess: true, data });

async function fulfillJson(route: Route, data: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify(data),
  });
}

async function mockReportApis(page: Page, role: DemoRole) {
  await page.route("**/api/v1/**", async (route) => {
    const requestUrl = new URL(route.request().url());
    const pathname = requestUrl.pathname;

    if (pathname === "/api/v1/auth/refresh") {
      await fulfillJson(
        route,
        envelope({
          accessToken: "landing-recording-token",
          accessTokenExpiresAt: "2027-08-06T12:00:00+09:00",
        }),
      );
      return;
    }

    if (pathname === "/api/v1/members/me") {
      await fulfillJson(
        route,
        envelope({
          email: "demo@zani.kr",
          displayName: role === "INSTRUCTOR" ? "박서준" : "김민지",
          profileImageUrl: null,
          reportEmailEnabled: true,
        }),
      );
      return;
    }

    if (pathname === "/api/v1/sessions") {
      await fulfillJson(
        route,
        envelope([
          {
            sessionId: demoSessionId,
            inviteCode: "ZANI-DEMO",
            title: "React 상태 관리 심화",
            instructorName: "박서준",
            status: "ENDED",
            role,
            startedAt: "2026-08-04T14:00:00+09:00",
            endedAt: "2026-08-04T15:15:00+09:00",
            participantCount: 24,
            reportStatus: "COMPLETED",
            rejoinable: false,
            thumbnailUrl: null,
          },
        ]),
      );
      return;
    }

    if (pathname.endsWith("/reports/instructor")) {
      await fulfillJson(
        route,
        envelope({
          overallFeedback:
            "핵심 개념을 단계적으로 설명해 수업 흐름이 안정적이었습니다. 상태 변경이 화면에 반영되는 구간에서는 짧은 확인 질문을 더하면 이해를 단단하게 만들 수 있어요.",
          stats: {
            studentCount: 24,
            durationSeconds: 4500,
            questionCount: 7,
            alertCount: 3,
          },
          scores: [
            { evaluationType: "DELIVERY", score: 88 },
            { evaluationType: "STRUCTURE_FLOW", score: 91 },
            { evaluationType: "INTERACTION", score: 82 },
            { evaluationType: "DIFFICULTY_CONTROL", score: 85 },
          ],
          insights: [
            {
              title: "상태 변경의 흐름을 차근차근 연결했어요",
              content: "예제 코드와 화면 변화를 함께 보여줘 핵심 개념을 따라가기 쉬웠습니다.",
              suggestion: "다음 수업에서도 개념 설명 직후 짧은 확인 질문을 이어가 보세요.",
              startedOffsetMs: 960000,
              endedOffsetMs: 1260000,
            },
            {
              title: "렌더링 조건에서 확인 신호가 모였어요",
              content: "조건부 렌더링 예제 구간에서 확인이 필요한 집단 신호가 잠시 증가했습니다.",
              suggestion: "비교 예제를 한 번 더 보여주고 학생들의 이해 여부를 확인해 보세요.",
              startedOffsetMs: 2460000,
              endedOffsetMs: 2760000,
            },
          ],
        }),
      );
      return;
    }

    if (pathname.endsWith("/reports/student")) {
      await fulfillJson(
        route,
        envelope({
          activity: {
            publicChatCount: 4,
            confusedCount: 2,
            missedCount: 1,
            questionCount: 3,
          },
          participationSummary:
            "수업 전반의 흐름을 안정적으로 따라왔고, 상태 업데이트 순서를 다룬 구간에서 확인 응답을 남겼어요. 해당 구간을 짧게 다시 보면 다음 내용과 더 자연스럽게 연결됩니다.",
          recommendations: [
            {
              recommendationType: "CONFUSED",
              title: "상태 업데이트가 반영되는 순서",
              description: "헷갈려요 응답을 남긴 구간이에요. 예제의 상태 변화를 다시 확인해 보세요.",
              startSeconds: 1180,
              endSeconds: 1390,
            },
            {
              recommendationType: "MISSED",
              title: "조건부 렌더링 핵심 예제",
              description: "놓친 흐름과 연결되는 구간이에요. 분기 조건을 중심으로 다시 살펴보세요.",
              startSeconds: 2470,
              endSeconds: 2680,
            },
          ],
        }),
      );
      return;
    }

    if (pathname.endsWith("/reports/attention/group")) {
      await fulfillJson(route, envelope(groupAttentionData));
      return;
    }

    if (pathname.endsWith("/reports/attention/me")) {
      await fulfillJson(route, envelope(studentAttentionData));
      return;
    }

    if (pathname.endsWith("/quiz")) {
      await fulfillJson(
        route,
        envelope({
          title: "React 상태 관리 핵심 점검",
          description: "수업의 핵심 개념을 간단히 확인해 보세요.",
          estimatedDurationMinutes: 5,
          submitted: false,
          questions: [
            {
              questionId: "q1",
              order: 1,
              text: "상태 변경 후 화면이 갱신되는 과정으로 알맞은 것은?",
              options: [
                { optionId: "o1", order: 1, text: "상태 변경 뒤 다시 렌더링된다" },
                { optionId: "o2", order: 2, text: "화면이 먼저 바뀐 뒤 상태가 저장된다" },
              ],
              grading: null,
            },
            {
              questionId: "q2",
              order: 2,
              text: "조건부 렌더링의 기준이 되는 값은?",
              options: [
                { optionId: "o3", order: 1, text: "현재 상태와 조건" },
                { optionId: "o4", order: 2, text: "파일의 생성 시간" },
              ],
              grading: null,
            },
          ],
        }),
      );
      return;
    }

    await fulfillJson(route, { isSuccess: false, data: null }, 404);
  });
}

const groupAttentionData = {
  durationSeconds: 4500,
  focusFlow: {
    intervalSeconds: 300,
    points: [
      { offsetSeconds: 0, focusLevel: 3.4, eligibleCount: 21 },
      { offsetSeconds: 300, focusLevel: 3.6, eligibleCount: 22 },
      { offsetSeconds: 600, focusLevel: 3.2, eligibleCount: 22 },
      { offsetSeconds: 900, focusLevel: 2.9, eligibleCount: 21 },
      { offsetSeconds: 1200, focusLevel: 2.4, eligibleCount: 20 },
      { offsetSeconds: 1500, focusLevel: 3.1, eligibleCount: 22 },
      { offsetSeconds: 1800, focusLevel: 3.5, eligibleCount: 23 },
      { offsetSeconds: 2100, focusLevel: 3.3, eligibleCount: 22 },
      { offsetSeconds: 2400, focusLevel: 2.7, eligibleCount: 21 },
      { offsetSeconds: 2700, focusLevel: 3.1, eligibleCount: 22 },
      { offsetSeconds: 3000, focusLevel: 3.6, eligibleCount: 23 },
      { offsetSeconds: 3300, focusLevel: 3.4, eligibleCount: 22 },
      { offsetSeconds: 3600, focusLevel: 3.7, eligibleCount: 23 },
      { offsetSeconds: 3900, focusLevel: 3.5, eligibleCount: 22 },
      { offsetSeconds: 4200, focusLevel: 3.8, eligibleCount: 22 },
    ],
  },
  signals: {
    intervalSeconds: 300,
    points: Array.from({ length: 15 }, (_, index) => ({
      offsetSeconds: index * 300,
      connectedCount: 24,
      eligibleCount: index % 4 === 0 ? 21 : 22,
      checkNeededRatio: index === 4 || index === 8 ? 0.32 : 0.1,
      cameraOffRatio: 0.08,
      confusedRatio: index === 4 ? 0.24 : 0.05,
      missedRatio: index === 8 ? 0.18 : 0.03,
      nonResponseRatio: 0.04,
      unmeasurableRatio: 0.08,
    })),
  },
  distractedIntervals: [
    { startSeconds: 1120, endSeconds: 1370 },
    { startSeconds: 2420, endSeconds: 2660 },
  ],
  sections: [
    {
      startSeconds: 0,
      endSeconds: 1080,
      title: "상태 관리의 기본 원리",
      summary: "상태와 화면 갱신의 관계를 예제로 살펴봤어요.",
      focusLevel: 3.5,
    },
    {
      startSeconds: 1080,
      endSeconds: 2280,
      title: "상태 업데이트 흐름",
      summary: "업데이트가 반영되는 순서와 렌더링 과정을 다뤘어요.",
      focusLevel: 2.8,
    },
    {
      startSeconds: 2280,
      endSeconds: 3300,
      title: "조건부 렌더링",
      summary: "조건에 따라 다른 화면을 만드는 방법을 연습했어요.",
      focusLevel: 3.0,
    },
    {
      startSeconds: 3300,
      endSeconds: 4500,
      title: "실전 예제와 정리",
      summary: "수업 내용을 하나의 예제로 연결하고 핵심을 정리했어요.",
      focusLevel: 3.6,
    },
  ],
};

const studentAttentionData = {
  durationSeconds: 4500,
  focusFlow: {
    intervalSeconds: 300,
    points: [
      { offsetSeconds: 0, focusLevel: 3.6 },
      { offsetSeconds: 300, focusLevel: 3.8 },
      { offsetSeconds: 600, focusLevel: 3.4 },
      { offsetSeconds: 900, focusLevel: 3.1 },
      { offsetSeconds: 1200, focusLevel: 2.5 },
      { offsetSeconds: 1500, focusLevel: 3.2 },
      { offsetSeconds: 1800, focusLevel: 3.7 },
      { offsetSeconds: 2100, focusLevel: 3.5 },
      { offsetSeconds: 2400, focusLevel: 2.6 },
      { offsetSeconds: 2700, focusLevel: 3.2 },
      { offsetSeconds: 3000, focusLevel: 3.8 },
      { offsetSeconds: 3300, focusLevel: 3.6 },
      { offsetSeconds: 3600, focusLevel: 3.9 },
      { offsetSeconds: 3900, focusLevel: 3.7 },
      { offsetSeconds: 4200, focusLevel: 3.9 },
    ],
  },
  stateIntervals: [
    { startSeconds: 1130, endSeconds: 1390, state: "CONFUSED" },
    { startSeconds: 2460, endSeconds: 2670, state: "MISSED" },
  ],
  sections: groupAttentionData.sections,
};

async function installRecordingChrome(page: Page) {
  await page.evaluate(() => {
    const style = document.createElement("style");
    style.textContent = `
      nextjs-portal { display: none !important; }
      [data-zani-recording-cursor] {
        position: fixed; left: 0; top: 0; z-index: 2147483646; width: 25px; height: 32px;
        pointer-events: none; transform: translate3d(58px, 88px, 0);
        transition: transform 680ms cubic-bezier(.22,.8,.28,1);
        filter: drop-shadow(0 2px 3px rgba(0,0,0,.32));
      }
      [data-zani-recording-cursor] svg { display: block; width: 25px; height: 32px; }
      [data-zani-recording-click] {
        position: absolute; left: -9px; top: -9px; width: 34px; height: 34px;
        border: 2px solid #62bf91; border-radius: 999px; opacity: 0; transform: scale(.5);
        transition: opacity 140ms ease, transform 220ms ease;
      }
      [data-zani-recording-cursor][data-clicking="true"] [data-zani-recording-click] {
        opacity: 1; transform: scale(1);
      }
      [data-zani-recording-curtain] {
        position: fixed; inset: 0; z-index: 2147483647; pointer-events: none;
        background: #fff; opacity: 1; transition: opacity 520ms ease;
      }
      [data-zani-recording-curtain][data-visible="false"] { opacity: 0; }
    `;
    document.head.appendChild(style);

    const cursor = document.createElement("div");
    cursor.setAttribute("data-zani-recording-cursor", "");
    cursor.innerHTML = `
      <svg viewBox="0 0 25 32" aria-hidden="true">
        <path d="M2 1.5V27l6.1-6.1 4.2 8.1 4.1-2.1-4.1-7.8H23L2 1.5Z" fill="#fff" stroke="#111827" stroke-width="2" stroke-linejoin="round"/>
      </svg>
      <span data-zani-recording-click></span>
    `;
    document.body.appendChild(cursor);

    const curtain = document.createElement("div");
    curtain.setAttribute("data-zani-recording-curtain", "");
    curtain.setAttribute("data-visible", "true");
    document.body.appendChild(curtain);
  });
}

async function setCurtain(page: Page, visible: boolean) {
  await page.locator("[data-zani-recording-curtain]").evaluate((element, nextVisible) => {
    element.setAttribute("data-visible", nextVisible ? "true" : "false");
  }, visible);
}

async function moveCursorTo(page: Page, target: Locator) {
  await expect(target).toBeVisible();
  const box = await target.boundingBox();
  if (box === null) throw new Error("Unable to locate recording target.");

  await page.locator("[data-zani-recording-cursor]").evaluate((element, point) => {
    (element as HTMLElement).style.transform = `translate3d(${point.x}px, ${point.y}px, 0)`;
  }, {
    x: box.x + box.width / 2 - 5,
    y: box.y + box.height / 2 - 5,
  });
  await page.waitForTimeout(760);
}

async function moveCursorAndClick(page: Page, target: Locator) {
  await moveCursorTo(page, target);
  const cursor = page.locator("[data-zani-recording-cursor]");
  await cursor.evaluate((element) => element.setAttribute("data-clicking", "true"));
  await target.click();
  await page.waitForTimeout(360);
  await cursor.evaluate((element) => element.removeAttribute("data-clicking"));
}

const scenarios: DemoScenario[] = [
  {
    path: "/dev/landing-recording/live-classroom",
    output: "landing-live-classroom.png",
    ready: (page) => page.getByText("React 상태관리 심화"),
    run: async (page) => {
      await page.waitForTimeout(800);
      await moveCursorTo(page, page.getByRole("button", { name: /발표자 보기/ }));
      await page.waitForTimeout(450);
    },
  },
  {
    path: "/dev/landing-recording/browser-analysis",
    output: "landing-browser-analysis.png",
    ready: (page) => page.getByText("React 상태관리 심화"),
    run: async (page) => {
      await page.waitForTimeout(850);
      await moveCursorAndClick(page, page.getByRole("button", { name: "카메라 끄기" }));
      await expect(page.getByTestId("analysis-status-notice")).toBeVisible();
      await page.waitForTimeout(500);
    },
  },
  {
    path: "/dev/landing-recording/student-prompt",
    output: "during-student-prompt.png",
    ready: (page) => page.getByRole("group", { name: "잠깐 확인할게요" }),
    run: async (page) => {
      await page.waitForTimeout(900);
      await moveCursorTo(page, page.getByRole("button", { name: /헷갈려요/ }));
      await page.waitForTimeout(450);
    },
  },
  {
    path: "/dev/landing-recording/instructor-tip",
    output: "during-instructor-tip.png",
    ready: (page) => page.getByTestId("coach-tip-card"),
    run: async (page) => {
      await page.waitForTimeout(900);
      await moveCursorTo(page, page.getByRole("button", { name: "확인" }));
      await page.waitForTimeout(450);
    },
  },
  {
    path: `/my-lectures/${demoSessionId}/report?tab=report`,
    output: "after-instructor-report.webm",
    role: "INSTRUCTOR",
    ready: (page) => page.getByText("종합 포인트"),
    run: async (page) => {
      await page.waitForTimeout(800);
      await moveCursorTo(page, page.getByText("분야별 평가"));
      await page.mouse.wheel(0, 470);
      await page.waitForTimeout(1_700);
    },
  },
  {
    path: `/my-lectures/${demoSessionId}/report?tab=report`,
    output: "after-student-report.webm",
    role: "STUDENT",
    ready: (page) => page.getByText("수업 참여도 요약"),
    run: async (page) => {
      await page.waitForTimeout(800);
      await moveCursorTo(page, page.getByText("나의 복습 추천"));
      await page.mouse.wheel(0, 430);
      await page.waitForTimeout(1_700);
    },
  },
];

test.describe.configure({ mode: "serial" });

test.beforeAll(async () => {
  await rm(tempVideoDir, { recursive: true, force: true });
  await mkdir(tempVideoDir, { recursive: true });
  await mkdir(assetDir, { recursive: true });
});

test.afterAll(async () => {
  await rm(tempVideoDir, { recursive: true, force: true });
});

for (const scenario of scenarios) {
  test(`creates ${scenario.output} from the real product UI`, async ({ browser, baseURL }) => {
    const isVideo = scenario.output.endsWith(".webm");
    const context = await browser.newContext({
      viewport: videoSize,
      deviceScaleFactor: 1,
      colorScheme: "light",
      reducedMotion: "no-preference",
      ...(isVideo ? { recordVideo: { dir: tempVideoDir, size: videoSize } } : {}),
    });
    const page = await context.newPage();
    const video = isVideo ? page.video() : null;

    try {
      if (scenario.role !== undefined) await mockReportApis(page, scenario.role);

      await page.goto(`${baseURL}${scenario.path}`, { waitUntil: "domcontentloaded" });
      await expect(scenario.ready(page)).toBeVisible({ timeout: 15_000 });

      const inviteClose = page.getByRole("button", { name: "초대 안내 닫기" });
      if (await inviteClose.isVisible()) await inviteClose.click();

      await page.evaluate(() => document.fonts.ready);
      await installRecordingChrome(page);
      await page.waitForTimeout(250);
      await setCurtain(page, false);
      await page.waitForTimeout(650);

      await scenario.run(page);

      if (!isVideo) {
        await page.screenshot({
          path: path.join(assetDir, scenario.output),
          type: "png",
          animations: "disabled",
        });
      }

      await setCurtain(page, true);
      await page.waitForTimeout(620);
    } finally {
      await context.close();
    }

    if (isVideo) {
      if (video === null) throw new Error("Playwright did not create a video recorder.");
      await video.saveAs(path.join(assetDir, scenario.output));
    }
  });
}
