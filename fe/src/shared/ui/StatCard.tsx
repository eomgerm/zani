import type { ReactNode } from "react";

interface StatCardProps {
  /** 상단 라벨 (아이콘 포함 노드 허용) */
  label: ReactNode;
  /** 큰 수치 값 */
  value: ReactNode;
  /** 값 오른쪽 보조 뱃지 (예: "보통") */
  suffix?: ReactNode;
}

/**
 * 테두리형 통계 카드. 리포트 "한눈에 보기" 그리드에서 재사용한다.
 */
export function StatCard({ label, value, suffix }: StatCardProps) {
  return (
    <div className="z-box px-[18px] py-4">
      <div className="mb-2.5 flex items-center gap-2 text-[12.5px] text-ink-faint">{label}</div>
      <div className="flex items-baseline gap-[7px]">
        <span className="text-2xl font-black tracking-[-.5px]">{value}</span>
        {suffix}
      </div>
    </div>
  );
}
