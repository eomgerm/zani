/**
 * 집중 흐름 차트 상단 헤더. 제목 + 집계 범위 칩 + 0~4 색상 범례.
 * 강사/학생 리포트가 동일한 형태를 쓰므로 분리했다.
 */
export function FocusLegend({ title, scope }: { title: string; scope: string }) {
  return (
    <div className="mb-1.5 flex flex-wrap items-center justify-between gap-2.5">
      <div className="z-section-title">
        <span className="text-primary">📈</span>
        {title}
      </div>
      <div className="flex flex-wrap gap-3.5 text-xs font-bold text-ink-muted">
        <span className="rounded-full bg-[#eaf7f2] px-2.5 py-[3px] text-[11px] font-extrabold text-primary-deep">
          {scope}
        </span>
        <span className="flex items-center gap-[7px]">
          <span className="h-2 w-9 rounded-full bg-[linear-gradient(90deg,#e0455f,#f4c325,#16c582)]" />
          0 낮음 → 4 높음
        </span>
      </div>
    </div>
  );
}
