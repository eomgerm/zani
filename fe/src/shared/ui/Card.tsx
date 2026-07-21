import type { ReactNode } from "react";

interface CardProps {
  children: ReactNode;
  /** 추가 클래스. 여백은 여기서 지정한다(기본 p-[22px]) */
  className?: string;
}

/**
 * 흰색 라운드 카드 컨테이너. 앱 화면 전반에서 콘텐츠 블록의 기본 표면으로 재사용한다.
 * 표면 스타일은 globals.css의 `.z-card`에 정의되어 있다.
 */
export function Card({ children, className = "p-[22px]" }: CardProps) {
  return <div className={`z-card ${className}`}>{children}</div>;
}
