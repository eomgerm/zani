"use client";

import type { ReactNode } from "react";

import {
  EvalDonuts,
  PictoBars,
  PictoBell,
  PictoChat,
  PictoClock,
  PictoClockMuted,
  PictoInbox,
  PictoLock,
  PictoPeople,
  PictoWarn,
} from "@/shared/ui";
import {
  focusedIntervalRatio,
  focusedRatioBand,
  formatOffset,
  GroupAttentionTimeline,
  useGroupAttentionTimeline,
  useInstructorReport,
  type GroupTimelineRequester,
  type InstructorInsight,
  type InstructorReport as InstructorReportData,
  type InstructorReportRequester,
  type InstructorReportStats,
  type InstructorReportStatus,
  type InstructorScore,
  type InstructorTip,
} from "@/domains/report";

interface Props {
  /** 리포트와 참여도 타임라인을 조회할 실제 세션 id. */
  sessionId: string;
  /** 구간 상세에서 클립 탭으로 옮긴다. */
  onJumpToClip: (offsetSeconds: number) => void;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  request?: InstructorReportRequester;
  /** 집중 흐름 조회를 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  attentionRequest?: GroupTimelineRequester;
}

/**
 * 리포트 탭 2 (강사) — 한눈에 보기 · 집중 흐름 · 타임라인 · AI 수업 피드백.
 *
 * <p>제목은 박스 밖에 두고 내용만 박스에 담는다(디자인 문서 §6). 집중 흐름과 타임라인은 한
 * 응답에서 나오므로 report 도메인 컴포넌트가 두 블록을 함께 그린다.
 *
 * <p><b>응답 둘을 합쳐 그린다.</b> 리포트 본문(종합 피드백·평가·인사이트·팁·집계)은
 * `GET /reports/instructor`(109) 가 주고, 집중 흐름과 수업 내용 구간은
 * `GET /reports/attention/group` 이 준다. 한눈에 보기의 "집중 구간 비율" 만 뒤쪽 응답에서 세므로
 * 조회는 여기서 한 번 하고 카드에는 `source` 로 넘긴다 — 카드가 자기 몫을 또 부르면 같은 큰 응답을
 * 한 화면에서 두 번 받는다.
 *
 * <p><b>두 응답의 실패를 묶지 않는다.</b> 리포트를 못 받아도 집중 흐름은 그린다. 서로 다른
 * 엔드포인트라 한쪽이 죽었다고 다른 쪽까지 감출 이유가 없다.
 */
export function InstructorReport({
  sessionId,
  onJumpToClip,
  request,
  attentionRequest,
}: Props) {
  const { status, report, retry } = useInstructorReport({ sessionId, request });
  const attention = useGroupAttentionTimeline(sessionId, attentionRequest);

  // 집중 흐름이 아직 안 왔으면 비율 칸만 비운다. 나머지 넷은 리포트 응답만 있으면 그릴 수 있다.
  const focusedRatio =
    attention.timeline === null ? null : focusedIntervalRatio(attention.timeline.focusFlow.points);

  return (
    <>
      <div className="z-report-head">
        <div className="z-section-title">한눈에 보기</div>
      </div>
      {report === null ? (
        <ReportNotice status={status} retry={retry} />
      ) : (
        <Glance stats={report.stats} focusedRatio={focusedRatio} />
      )}

      <GroupAttentionTimeline sessionId={sessionId} source={attention} onJumpToClip={onJumpToClip} />

      {report !== null && <Feedback report={report} onJumpToClip={onJumpToClip} />}
    </>
  );
}

/* ------------------------------------------------------------------ 한눈에 보기 */

/** 값을 아직 알 수 없는 칸. 0 을 쓰면 "없음" 으로 읽혀 다른 사실이 된다. */
const UNKNOWN = "—";

/**
 * `7500` → `2시간 5분`.
 *
 * <p>종료 시각을 저장하기 전에 끝난 과거 세션은 0 이 온다(서버 계약). 그때는 "0분" 이 아니라
 * 모른다고 적는다 — 0 분짜리 수업은 없다.
 */
function koreanDuration(seconds: number): string {
  if (seconds <= 0) return UNKNOWN;

  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  // 1 분이 안 되는 수업도 "0분" 대신 "1분" 으로 적는다. 있었던 수업을 없던 것처럼 보이게 하지 않는다.
  if (hours === 0) return `${Math.max(1, minutes)}분`;
  return minutes === 0 ? `${hours}시간` : `${hours}시간 ${minutes}분`;
}

function Glance({
  stats,
  focusedRatio,
}: {
  stats: InstructorReportStats;
  focusedRatio: number | null;
}) {
  const cells: { icon: ReactNode; label: string; value: string; badge?: string }[] = [
    { icon: <PictoPeople size={18} />, label: "총 수강생", value: `${stats.studentCount}명` },
    {
      icon: <PictoClock size={18} />,
      label: "수업 시간",
      value: koreanDuration(stats.durationSeconds),
    },
    {
      icon: <PictoChat size={18} />,
      label: "질문 수",
      // null 은 "질문이 없었다" 가 아니라 "아직 셀 수 없다" 다(서버 계약).
      value: stats.questionCount === null ? UNKNOWN : `${stats.questionCount}개`,
    },
    {
      icon: <PictoBars size={18} />,
      label: "집중 구간 비율",
      value: focusedRatio === null ? UNKNOWN : `${focusedRatio}%`,
      // 숫자만 두면 78% 가 좋은 것인지 알 수 없다. 잴 수 없었으면 평가도 붙이지 않는다.
      badge: focusedRatio === null ? undefined : focusedRatioBand(focusedRatio),
    },
    { icon: <PictoBell size={18} />, label: "이해도 알림", value: `${stats.alertCount}회` },
  ];

  return (
    <div className="grid grid-cols-5 gap-3.5">
      {cells.map((cell) => (
        <div key={cell.label} className="z-report-box px-5 py-[18px]">
          <div className="mb-3 flex items-center gap-2 text-[13px] font-medium text-ink-faint">
            {cell.icon}
            {cell.label}
          </div>
          <div className="flex items-baseline gap-[7px]">
            <span className="text-[26px] font-extrabold tracking-[-.5px]">{cell.value}</span>
            {cell.badge !== undefined && (
              <span className="rounded-md bg-warn-soft px-[7px] py-0.5 text-xs font-extrabold text-warn-text">
                {cell.badge}
              </span>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

/* -------------------------------------------------------------- AI 수업 피드백 */

/**
 * 분야별 평가의 이름·색·설명.
 *
 * <p>서버는 enum 문자열만 준다. 무엇을 잰 값인지 강사에게 설명하는 일은 화면 몫이라 여기 둔다.
 * 색은 분야마다 고정이며 점수에 따라 바뀌지 않는다(296 시안) — 색이 점수를 따라가면 도넛 길이와
 * 색이 같은 말을 두 번 하고, 분야끼리 견주기 어려워진다.
 */
const EVALUATIONS: Record<string, { name: string; color: string; desc: string }> = {
  DELIVERY: {
    name: "전달력",
    color: "#10b981",
    desc: "말의 속도·명료함과 핵심 개념을 짚어주는 정도를 평가했어요.",
  },
  STRUCTURE_FLOW: {
    name: "수업 구성",
    color: "#15bd7d",
    desc: "수업 순서와 주제 전환이 자연스럽게 이어졌는지 평가했어요.",
  },
  INTERACTION: {
    name: "상호작용",
    color: "#f4c325",
    desc: "질문 응답, 채팅·반응 대응 등 학생과의 소통을 평가했어요.",
  },
  DIFFICULTY_CONTROL: {
    name: "난이도 조절",
    color: "#e0714f",
    desc: "학생 이해도에 맞춰 설명 깊이와 속도를 조절했는지 평가했어요.",
  },
};

/** 모르는 분야가 와도 버리지 않는다. AI 가 쓰는 분류가 늘면 이름 없이라도 점수는 보여야 한다. */
const toDonut = (score: InstructorScore) => {
  const known = EVALUATIONS[score.evaluationType];
  return {
    name: known?.name ?? score.evaluationType,
    value: score.score,
    color: known?.color ?? "#8a90b4",
    desc: known?.desc,
  };
};

function Feedback({
  report,
  onJumpToClip,
}: {
  report: InstructorReportData;
  onJumpToClip: (offsetSeconds: number) => void;
}) {
  return (
    <>
      <div className="z-report-head">
        <div className="z-section-title">AI 수업 피드백</div>
      </div>
      <div className="z-report-box mb-[22px] px-5 py-4">
        <div className="mb-2 text-[13.5px] font-extrabold">종합 포인트</div>
        <p className="text-[13px] leading-[1.75] text-ink-sub">
          {report.overallFeedback.length > 0
            ? report.overallFeedback
            : "이 수업의 종합 피드백은 아직 없어요."}
        </p>
      </div>

      {/* 두 블록은 같은 평가의 두 면이라 높이를 맞춰 나란히 둔다. */}
      <div className="grid grid-cols-2 items-stretch gap-[26px]">
        <div className="flex flex-col">
          <div className="mb-3.5 text-sm font-extrabold">분야별 평가</div>
          <div className="z-report-box flex flex-1 items-center px-4 py-[18px]">
            {report.scores.length > 0 ? (
              <EvalDonuts data={report.scores.map(toDonut)} />
            ) : (
              <p className="w-full py-6 text-center text-[13px] text-ink-fainter">
                분야별 평가가 아직 없어요.
              </p>
            )}
          </div>
        </div>

        <div className="flex flex-col">
          <div className="mb-3.5 text-sm font-extrabold">수업 인사이트</div>
          <Insights insights={report.insights} tips={report.tips} onJumpToClip={onJumpToClip} />
        </div>
      </div>
    </>
  );
}

/**
 * 관찰(인사이트)과 해 볼 것(팁).
 *
 * <p><b>둘을 짝지어 한 장으로 만들지 않는다.</b> 296 시안의 카드는 관찰과 TIP 이 한 장에 붙어
 * 있지만, 서버는 둘을 독립된 목록으로 주고 `insightType`·`tipType` 의 분류 체계도 서로 다르다.
 * 순서로 짝지으면 개수가 어긋나는 순간 엉뚱한 관찰에 엉뚱한 처방이 붙는다 — 강사가 그 말을 믿고
 * 다음 수업을 바꾸는 자리라 지어내면 안 된다. 카드 모양은 시안대로 두고 안쪽만 나눈다.
 *
 * <p>인사이트에 구간이 있으면 눌러서 그 자리로 간다. 팁은 수업 전체에 대한 말이라 이동이 없다.
 */
function Insights({
  insights,
  tips,
  onJumpToClip,
}: {
  insights: readonly InstructorInsight[];
  tips: readonly InstructorTip[];
  onJumpToClip: (offsetSeconds: number) => void;
}) {
  if (insights.length === 0 && tips.length === 0) {
    return (
      <div className="z-report-box flex flex-1 items-center justify-center px-4 py-6">
        <p className="text-center text-[13px] text-ink-fainter">수업 인사이트가 아직 없어요.</p>
      </div>
    );
  }

  return (
    // 항목 수가 응답마다 달라 옆 칸(도넛)보다 길어질 수 있다. 복습 추천과 같게 안에서 스크롤한다.
    <div className="flex max-h-[420px] min-h-0 flex-1 flex-col gap-3 overflow-y-auto">
      {insights.map((insight, index) => (
        <InsightCard
          key={`insight-${index}-${insight.startSeconds ?? "all"}`}
          insight={insight}
          onJumpToClip={onJumpToClip}
        />
      ))}
      {tips.map((tip, index) => (
        <div key={`tip-${index}-${tip.title}`} className="z-report-box px-4 py-3.5">
          <div className="mb-1.5 flex items-start gap-1.5 text-[12.5px] font-extrabold">
            <CheckMark />
            <span>{tip.title.length > 0 ? tip.title : "개선 팁"}</span>
          </div>
          {/* 해 볼 것은 관찰과 달리 행동이라 이름을 붙여 초록으로 짚어 준다. */}
          <div className="text-[11.5px] font-bold leading-[1.5] text-primary-dark">
            <span className="mr-1 font-extrabold">TIP.</span>
            {tip.content}
          </div>
        </div>
      ))}
    </div>
  );
}

function InsightCard({
  insight,
  onJumpToClip,
}: {
  insight: InstructorInsight;
  onJumpToClip: (offsetSeconds: number) => void;
}) {
  const at = insight.startSeconds;

  const body = (
    <>
      <div className="mb-1.5 flex items-center gap-1.5 text-[12.5px] font-extrabold">
        <CheckMark />
        {at === null ? (
          <span className="text-ink-faint">수업 전체</span>
        ) : (
          <span className="font-mono text-primary">
            {formatOffset(at)}
            {insight.endSeconds !== null && insight.endSeconds > at
              ? ` ~ ${formatOffset(insight.endSeconds)}`
              : ""}
          </span>
        )}
      </div>
      <p className="text-[11.5px] leading-[1.5] text-ink-faint">{insight.content}</p>
    </>
  );

  // 구간이 없는 인사이트는 갈 곳이 없다. 눌리지 않는 버튼을 두느니 버튼을 만들지 않는다.
  return at === null ? (
    <div className="z-report-box px-4 py-3.5">{body}</div>
  ) : (
    <button
      type="button"
      onClick={() => onJumpToClip(at)}
      className="z-report-box cursor-pointer px-4 py-3.5 text-left hover:border-line-primary hover:bg-[#f6faf8]"
    >
      {body}
    </button>
  );
}

/* ------------------------------------------------------------------ 누락 상태 */

/**
 * 리포트를 그릴 수 없을 때 한눈에 보기 자리에 놓는 안내.
 *
 * <p>권한·미생성·실패를 한 문장으로 뭉치지 않는다. 셋은 강사가 할 일이 전부 다르다 — 순서대로
 * 다른 수업을 찾아야 하고, 기다려야 하고, 다시 눌러야 한다.
 */
function ReportNotice({
  status,
  retry,
}: {
  status: InstructorReportStatus;
  retry: () => void;
}) {
  if (status === "loading") {
    return (
      <Notice
        icon={<PictoClockMuted size={36} />}
        title="리포트를 불러오는 중이에요"
        detail="잠시만 기다려 주세요."
      />
    );
  }

  if (status === "forbidden") {
    return (
      <Notice
        icon={<PictoLock size={36} />}
        title="이 수업의 리포트를 볼 권한이 없어요"
        detail="내가 진행한 수업인지 확인해 주세요."
      />
    );
  }

  if (status === "notReady") {
    return (
      <Notice
        icon={<PictoInbox size={36} />}
        title="아직 리포트가 만들어지지 않았어요"
        detail="AI 분석이 끝나면 여기에서 확인할 수 있어요."
      />
    );
  }

  return (
    <div className="z-report-box px-5 py-12 text-center text-ink-fainter">
      <div className="mb-3 flex justify-center">
        <PictoWarn size={36} />
      </div>
      <div className="mb-1 font-bold text-ink-muted">리포트를 불러오지 못했어요</div>
      <button type="button" onClick={retry} className="z-btn z-btn-outline z-btn-md mt-3">
        다시 시도
      </button>
    </div>
  );
}

const Notice = ({ icon, title, detail }: { icon: ReactNode; title: string; detail: string }) => (
  <div className="z-report-box px-5 py-12 text-center text-ink-fainter">
    <div className="mb-3 flex justify-center">{icon}</div>
    <div className="mb-1 font-bold text-ink-muted">{title}</div>
    <div className="text-[13px]">{detail}</div>
  </div>
);

/** 인사이트 제목 앞의 체크. 카탈로그에 체크가 없어 같은 굵기로 그려 둔다. */
function CheckMark() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      className="mt-[3px] shrink-0 text-primary"
    >
      <path
        d="M5 12.8l4.4 4.2L19 7.4"
        stroke="currentColor"
        strokeWidth="2.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
