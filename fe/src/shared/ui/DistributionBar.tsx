import { color } from "@/shared/lib/theme";

interface DistributionBarProps {
  /** 좌측 라벨(예: 이해함/헷갈림/놓침) */
  label: string;
  /** 채움 비율(0~100) */
  percent: number;
  /** 채움 색 */
  fill: string;
  /** 우측 값 텍스트(예: "58%", "4회") */
  value: string;
  /** 라벨 폭(px) */
  labelWidth?: number;
}

/**
 * 라벨 + 가로 트랙 + 채움 + 값으로 구성된 분포 막대.
 * 리포트의 확인 프롬프트 응답 분포, 강의실 집단 알림 분포 등에서 재사용한다.
 */
export function DistributionBar({
  label,
  percent,
  fill,
  value,
  labelWidth = 52,
}: DistributionBarProps) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
      <span style={{ width: labelWidth, fontSize: 12, color: color.textMuted }}>
        {label}
      </span>
      <span
        style={{
          flex: 1,
          height: 8,
          background: "#eef0f7",
          borderRadius: 6,
          overflow: "hidden",
        }}
      >
        <span
          style={{
            display: "block",
            height: "100%",
            width: `${percent}%`,
            background: fill,
            borderRadius: 6,
          }}
        />
      </span>
      <span
        style={{
          width: 34,
          textAlign: "right",
          fontSize: 12,
          fontWeight: 700,
        }}
      >
        {value}
      </span>
    </div>
  );
}
