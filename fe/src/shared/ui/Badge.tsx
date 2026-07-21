import type { CSSProperties, ReactNode } from "react";

interface BadgeProps {
  children: ReactNode;
  /** 배경색 */
  bg: string;
  /** 글자색 */
  fg: string;
  style?: CSSProperties;
}

/**
 * 상태·역할 표시용 알약(pill) 뱃지. 강의 상태, 역할 칩, 리포트 태그 등에 재사용한다.
 */
export function Badge({ children, bg, fg, style }: BadgeProps) {
  return (
    <span
      style={{
        display: "inline-block",
        padding: "3px 10px",
        borderRadius: 8,
        fontSize: 12,
        fontWeight: 800,
        whiteSpace: "nowrap",
        background: bg,
        color: fg,
        ...style,
      }}
    >
      {children}
    </span>
  );
}
