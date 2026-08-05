import { ReportScreen } from "@/domains/lecture";

/**
 * 어느 탭으로 열지, 어느 시각에서 시작할지는 주소가 정한다(`?tab=report`, `?tab=clip&seek=1450`).
 * 퀴즈처럼 리포트에서 떠난 화면이 돌아올 때 보던 자리로 되돌리려는 것이다.
 *
 * <p>여기(서버)에서 읽는 이유: 클라이언트에서 `useSearchParams` 를 쓰면 정적 렌더링 때문에 Suspense
 * 경계를 따로 둬야 한다. 주소는 이미 서버가 들고 있으므로 값만 내려 준다.
 */
export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ sessionId: string }>;
  searchParams: Promise<{ tab?: string; seek?: string }>;
}) {
  const { sessionId } = await params;
  const { tab, seek } = await searchParams;

  return (
    <ReportScreen
      lectureId={sessionId}
      initialTab={tab === "report" ? "report" : "clip"}
      initialSeekSeconds={secondsOf(seek)}
    />
  );
}

/** 주소는 사람이 고칠 수 있다. 숫자가 아니거나 음수면 이동 요청이 없는 것으로 본다. */
function secondsOf(seek: string | undefined): number | null {
  if (seek === undefined) return null;
  const seconds = Number(seek);
  return Number.isFinite(seconds) && seconds >= 0 ? Math.floor(seconds) : null;
}
