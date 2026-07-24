import type { ReactNode } from "react";

interface BadgeProps {
  children: ReactNode;
  /** 배경색 (상태·태그별로 런타임에 결정되므로 인라인으로 전달) */
  bg: string;
  /** 글자색 */
  fg: string;
  className?: string;
}

/**
 * 상태·역할 표시용 알약 뱃지. 강의 상태, 역할 칩, 리포트 태그 등에 재사용한다.
 * 형태는 `.z-badge`, 색상만 런타임 값으로 지정한다.
 */
export function Badge({ children, bg, fg, className = "" }: BadgeProps) {
  return (
    <span className={`z-badge ${className}`} style={{ background: bg, color: fg }}>
      {children}
    </span>
  );
}
