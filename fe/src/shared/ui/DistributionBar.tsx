interface DistributionBarProps {
  /** 좌측 라벨(예: 이해함/헷갈림/놓침) */
  label: string;
  /** 채움 비율(0~100) */
  percent: number;
  /** 채움 색 (항목별 런타임 값) */
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
    <div className="flex items-center gap-[9px]">
      <span className="text-xs text-ink-muted" style={{ width: labelWidth }}>
        {label}
      </span>
      <span className="h-2 flex-1 overflow-hidden rounded-md bg-[#eef0f7]">
        <span
          className="block h-full rounded-md"
          style={{ width: `${percent}%`, background: fill }}
        />
      </span>
      <span className="w-[34px] text-right text-xs font-bold">{value}</span>
    </div>
  );
}
