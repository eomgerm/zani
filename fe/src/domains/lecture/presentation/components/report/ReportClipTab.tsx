import {
  SessionSummaryCard,
  StudentReportClip,
  InstructorReportClip,
  type ClipSeekRequest,
} from "@/domains/report";

interface Props {
  title: string;
  /** 녹화·전사·수업 요약을 조회할 실제 세션 id. */
  sessionId: string;
  /**
   * 역할이 부르는 엔드포인트를 가른다. 학생은 복습 클립(`/reports/student`), 강사는 수업
   * 클립(`/reports/instructor/clip`)이다 — 서로의 경로를 부르면 403 만 받는다(308).
   */
  isStudent: boolean;
  /**
   * 리포트 탭의 구간 상세가 "클립 바로가기"로 넘긴 이동 요청.
   *
   * <p>같은 시각을 연달아 눌러도 두 번째가 묻히지 않도록 nonce 를 함께 받는다. 두 역할 모두
   * 실제 플레이어가 그 자리로 이동한다.
   */
  seekRequest?: ClipSeekRequest | null;
  /**
   * 수업 요약의 구간 시각을 눌렀을 때 녹화를 그 자리로 옮긴다.
   *
   * <p>요청을 여기서 곧장 플레이어에 꽂지 않고 바깥으로 올린다 — `seekRequest` 를 들고 있는 곳이
   * 화면(ReportScreen)이라, 카드가 만든 이동도 같은 문으로 들어와야 nonce 가 한 줄로 이어진다.
   */
  onSeek?: (offsetSeconds: number) => void;
}

/** 리포트 탭 1 (수업 클립 / 복습 클립): 강의 영상 + 수업 내용 전사 + AI 요약 문서. */
export function ReportClipTab({
  title,
  sessionId,
  isStudent,
  seekRequest = null,
  onSeek,
}: Props) {
  return (
    <>
      <div className="mb-5">
        {isStudent ? (
          <StudentReportClip sessionId={sessionId} title={title} seekRequest={seekRequest} />
        ) : (
          <InstructorReportClip sessionId={sessionId} title={title} seekRequest={seekRequest} />
        )}
      </div>

      {/* 수업 요약 — isStudent 분기 밖에 두는 것이 의도다. 강사와 학생이 같은 문장을 본다(FRD §21). */}
      <SessionSummaryCard sessionId={sessionId} onSeek={onSeek} />
    </>
  );
}
