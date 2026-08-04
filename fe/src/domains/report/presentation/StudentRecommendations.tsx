"use client";

import { Badge, Card } from "@/shared/ui";
import type { StudentReportRequester } from "../infrastructure/studentReportApi";
import { formatOffset } from "./TimelineStatusBar";
import { useStudentReport } from "./useStudentReport";

/**
 * 근거 유형 배지. 서버 enum 이 확정되기 전의 방어적 매핑이라, 모르는 값은 버리지 않고
 * 기본 라벨로 그린다 — 근거 유형을 몰라도 추천 자체는 유효하다(REPORT-S-003 은 구간·이유
 * 표시를 요구하지 유형 명칭을 요구하지 않는다).
 */
const TYPE_BADGES: Record<string, { readonly label: string; readonly color: string }> = {
  CONFUSED: { label: "헷갈림", color: "#f4c325" },
  MISSED: { label: "놓침", color: "#10b981" },
  QUESTION: { label: "내 질문", color: "#15bd7d" },
  NON_RESPONSE: { label: "미응답", color: "#e0455f" },
  REPEATED_CHECK: { label: "반복 확인", color: "#10b981" },
  KEY_CONCEPT: { label: "중요 개념", color: "#8681c8" },
};

const badgeOf = (type: string) => TYPE_BADGES[type] ?? { label: "복습 추천", color: "#10b981" };

const Notice = ({ icon, title, detail }: { icon: string; title: string; detail?: string }) => (
  <div className="px-5 py-10 text-center text-ink-fainter">
    <div className="mb-3 text-[36px]">{icon}</div>
    <div className="mb-1 font-bold text-ink-muted">{title}</div>
    {detail !== undefined && <div className="text-[13px]">{detail}</div>}
  </div>
);

export interface StudentRecommendationsProps {
  readonly sessionId: string;
  /** 추천을 누르면 복습 클립으로 이동한다 — 탭 전환은 부모(리포트 화면)가 맡는다. */
  readonly onSeekToClip: (seconds: number) => void;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly request?: StudentReportRequester;
}

/**
 * 나의 복습 추천 카드(REPORT-S-002~005).
 *
 * <p>추천은 근거가 있을 때만 0~5개다. **0개는 성공 상태다** — "근거가 없었다"를 명확히
 * 말해야지, 빈 화면이나 오류로 보이면 안 된다(REPORT-S-005).
 *
 * <p>각 추천의 이동 목표는 자기 `startSeconds` 다. 개념 설명 시작 시각으로 서버가 맞춰
 * 내려준다(REPORT-S-004).
 */
export function StudentRecommendations({
  sessionId,
  onSeekToClip,
  request,
}: StudentRecommendationsProps) {
  const { status, report, retry } = useStudentReport({ sessionId, request });

  const body = () => {
    if (status === "loading") {
      return <Notice icon="⏳" title="복습 추천을 불러오는 중이에요" />;
    }

    if (status === "forbidden") {
      return (
        <Notice
          icon="🔒"
          title="복습 추천은 본인 것만 볼 수 있어요"
          detail="내가 참여한 수업이 맞는지 확인해 주세요."
        />
      );
    }

    if (status === "notReady") {
      return (
        <Notice
          icon="⏳"
          title="아직 분석이 끝나지 않았어요"
          detail="분석이 완료되면 복습 추천을 볼 수 있어요."
        />
      );
    }

    if (status === "failed" || report === null) {
      return (
        <div className="px-5 py-10 text-center text-ink-fainter">
          <div className="mb-3 text-[36px]">⚠️</div>
          <div className="mb-1 font-bold text-ink-muted">복습 추천을 불러오지 못했어요</div>
          <button type="button" onClick={retry} className="z-btn z-btn-outline z-btn-md mt-3">
            다시 시도
          </button>
        </div>
      );
    }

    if (report.recommendations.length === 0) {
      return (
        <Notice
          icon="✅"
          title="추천할 구간이 없어요"
          detail="복습이 필요하다고 볼 근거가 이 수업에서는 발견되지 않았어요."
        />
      );
    }

    return (
      <div className="flex flex-col gap-3">
        {report.recommendations.map((recommendation, index) => {
          const badge = badgeOf(recommendation.recommendationType);
          return (
            <button
              key={recommendation.id ?? `${recommendation.startSeconds}-${index}`}
              type="button"
              onClick={() => onSeekToClip(recommendation.startSeconds)}
              className="flex w-full cursor-pointer gap-3.5 rounded-[13px] border border-line-mint bg-transparent p-3 text-left hover:border-line-primary hover:bg-faint"
            >
              <span className="flex h-[50px] w-[74px] shrink-0 items-center justify-center rounded-[9px] bg-[#20233a]">
                <span className="flex size-[26px] items-center justify-center rounded-full bg-white/80 text-[11px] text-primary">
                  ▶
                </span>
              </span>
              <span className="min-w-0 flex-1">
                <span className="mb-1 flex flex-wrap items-center gap-2">
                  <span className="font-mono text-[11.5px] font-extrabold text-primary">
                    {formatOffset(recommendation.startSeconds)}
                  </span>
                  <Badge bg={`${badge.color}22`} fg={badge.color}>
                    {badge.label}
                  </Badge>
                  <span className="text-[13.5px] font-extrabold">{recommendation.title}</span>
                </span>
                <span className="block text-xs leading-[1.5] text-ink-faint">
                  {recommendation.reason}
                </span>
              </span>
            </button>
          );
        })}
      </div>
    );
  };

  return (
    <Card className="px-6 py-[22px]">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="z-section-title">
          <span className="text-danger">🎯</span>나의 복습 추천
        </div>
        <span className="text-[11.5px] text-ink-fainter">
          자기보고 · 질문 · 반복된 확인 필요가 결합된 구간만 골라요 (최대 5개)
        </span>
      </div>
      {body()}
    </Card>
  );
}
