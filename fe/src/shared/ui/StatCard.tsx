import type { ReactNode } from "react";
import { color } from "@/shared/lib/theme";

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
    <div style={{ border: `1px solid ${color.borderLight}`, borderRadius: 14, padding: "16px 18px" }}>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          fontSize: 12.5,
          color: color.textFaint,
          marginBottom: 10,
        }}
      >
        {label}
      </div>
      <div style={{ display: "flex", alignItems: "baseline", gap: 7 }}>
        <span style={{ fontSize: 24, fontWeight: 900, letterSpacing: "-.5px" }}>{value}</span>
        {suffix}
      </div>
    </div>
  );
}
