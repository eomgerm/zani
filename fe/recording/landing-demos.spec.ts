import { mkdir, rm } from "node:fs/promises";
import path from "node:path";
import { expect, test, type Locator, type Page } from "@playwright/test";

const videoSize = { width: 1280, height: 800 };
const tempVideoDir = path.resolve("recordings", ".video");
const assetDir = path.resolve("public", "asset");
type DemoScenario = {
  path: string;
  output: string;
  ready: (page: Page) => Locator;
  run: (page: Page) => Promise<void>;
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
