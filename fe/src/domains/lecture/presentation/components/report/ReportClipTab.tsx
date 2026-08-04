import { StudentReportClip, type ClipSeekRequest } from "@/domains/report";
import { summarySections, transcript } from "../../fixtures";

interface Props {
  title: string;
  /** 녹화·전사를 조회할 실제 세션 id. AI 요약은 아직 fixture 다. */
  sessionId: string;
  /**
   * 학생만 실데이터 패널을 그린다. 강사 클립 탭은 강사 리포트 API 가 생길 때까지 목업으로
   * 남는다 — 학생용 엔드포인트를 강사가 부르면 403 만 받는다.
   */
  isStudent: boolean;
  /**
   * 리포트 탭의 구간 상세가 "클립 바로가기"로 넘긴 이동 요청.
   *
   * <p>같은 시각을 연달아 눌러도 두 번째가 묻히지 않도록 nonce 를 함께 받는다. 학생은 실제
   * 플레이어가 그 자리로 이동하고, 강사는 아직 목업이라 어디로 가려 했는지만 적어 둔다.
   */
  seekRequest?: ClipSeekRequest | null;
}

/** 리포트 탭 1 (수업 클립 / 복습 클립): 강의 영상 + 수업 내용 전사 + AI 요약 문서. */
export function ReportClipTab({ title, sessionId, isStudent, seekRequest = null }: Props) {
  return (
    <>
      {isStudent ? (
        <div className="mb-5">
          <StudentReportClip sessionId={sessionId} title={title} seekRequest={seekRequest} />
        </div>
      ) : (
        <MockClipPanel title={title} seekSeconds={seekRequest?.seconds ?? null} />
      )}

      {/* AI 요약 문서 */}
      <div className="z-card px-7 py-6">
        <div className="z-section-title mb-4">수업 요약 레포트</div>
        <div className="flex flex-col gap-[18px]">
          {summarySections.map((s) => (
            <div key={s.h}>
              <div className="mb-1.5 text-[14.5px] font-extrabold">{s.h}</div>
              <p className="text-[13.5px] leading-[1.75] text-ink-sub">{s.p}</p>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

const pad = (value: number) => String(value).padStart(2, "0");

/**
 * 강사용 목업 패널. 강사 리포트 API 배선 전까지의 자리 표시자다.
 *
 * <p>여기 남은 재생 컨트롤 글리프(▶⏭🔊⛶)는 258 이 "113 이 실제 플레이어로 교체한다"며
 * 남겨 둔 것이다. 학생 경로는 실제 플레이어로 바뀌었고, 이 목업은 강사 리포트 티켓이 걷어낸다.
 */
function MockClipPanel({ title, seekSeconds }: { title: string; seekSeconds: number | null }) {
  const seekLabel =
    seekSeconds === null
      ? null
      : `${Math.floor(seekSeconds / 60)}:${pad(Math.round(seekSeconds % 60))}`;

  return (
    <div className="mb-5 grid grid-cols-[1.35fr_1fr] items-stretch gap-5">
      {/* 강의 영상 */}
      <div className="flex flex-col overflow-hidden rounded-2xl bg-panel-video shadow-[0_8px_30px_rgba(20,25,50,.22)]">
        <div className="relative flex aspect-video items-center justify-center bg-[linear-gradient(120deg,#1c2036,#20263f_55%,#1a1f34)]">
          <div className="flex size-[66px] items-center justify-center rounded-full bg-white/15 backdrop-blur-[4px]">
            <span className="ml-[5px] text-[22px] text-white">▶</span>
          </div>
          <div className="absolute inset-x-[22px] bottom-[18px]">
            <div className="truncate text-base font-extrabold text-white [text-shadow:0_2px_8px_rgba(0,0,0,.4)]">
              {title}
            </div>
            <div className="mt-0.5 text-xs text-panel-dim">
              {seekLabel === null ? "강의 다시보기" : `${seekLabel} 구간으로 이어서 보기`}
            </div>
          </div>
        </div>
        <div className="h-1 bg-[#2f3a37]">
          <div className="h-full w-[34%] bg-primary" />
        </div>
        <div className="flex items-center gap-4 px-4 py-3 text-[#c7ccf0]">
          <span className="text-[15px]">▶</span>
          <span className="text-[15px]">⏭</span>
          <span className="text-sm">🔊</span>
          <span className="font-mono text-[12.5px] text-panel-dim">42:30 / 2:05:30</span>
          <span className="flex-1" />
          <span className="text-[12.5px] font-bold">1.0x</span>
          <span className="text-sm">⛶</span>
        </div>
      </div>

      {/* 수업 내용 전사 */}
      <div className="relative min-h-[220px]">
        <div className="z-card absolute inset-0 flex flex-col overflow-hidden rounded-2xl">
          <div className="z-section-title shrink-0 border-b border-line-light px-[18px] py-[15px]">
            수업 내용
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-2 py-1.5">
            {transcript.map((t, i) => (
              <div
                key={i}
                className="flex cursor-pointer gap-3 rounded-[9px] px-2 py-[9px] hover:bg-[#f6faf8]"
              >
                <span className="w-[42px] shrink-0 font-mono text-xs font-bold text-primary">
                  {t.t}
                </span>
                <div className="text-[13px] leading-[1.55] text-ink-sub">
                  <span className="mr-1.5 font-bold text-ink-label">{t.speaker}</span>
                  {t.text}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
