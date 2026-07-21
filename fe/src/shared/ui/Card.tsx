import type { CSSProperties, ReactNode } from "react";
import { color, radius, shadow } from "@/shared/lib/theme";

interface CardProps {
  children: ReactNode;
  /** 내부 여백(px). 기본 22 */
  padding?: number | string;
  style?: CSSProperties;
}

/**
 * 흰색 라운드 카드 컨테이너. 앱 화면 전반에서 콘텐츠 블록의 기본 표면으로 재사용한다.
 */
export function Card({ children, padding = 22, style }: CardProps) {
  return (
    <div
      style={{
        background: color.surface,
        border: `1px solid ${color.border}`,
        borderRadius: radius.panel,
        padding,
        boxShadow: shadow.card,
        ...style,
      }}
    >
      {children}
    </div>
  );
}
