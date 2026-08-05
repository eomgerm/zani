import { ReportScreen } from "@/domains/lecture";

/**
 * 어느 탭으로 열지는 주소가 정한다(`?tab=report`). 퀴즈처럼 리포트 탭에서 떠난 화면이 돌아올 때
 * 클립 탭으로 떨어지지 않게 하려는 것이다.
 *
 * <p>여기(서버)에서 읽는 이유: 클라이언트에서 `useSearchParams` 를 쓰면 정적 렌더링 때문에 Suspense
 * 경계를 따로 둬야 한다. 주소는 이미 서버가 들고 있으므로 값만 내려 준다.
 */
export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ sessionId: string }>;
  searchParams: Promise<{ tab?: string }>;
}) {
  const { sessionId } = await params;
  const { tab } = await searchParams;
  return <ReportScreen lectureId={sessionId} initialTab={tab === "report" ? "report" : "clip"} />;
}
