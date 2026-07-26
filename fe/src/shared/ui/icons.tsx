/**
 * 프로토타입(ZANI.dc.html)의 인라인 SVG 아이콘을 컴포넌트로 옮긴 것.
 *
 * 색은 모두 `currentColor`를 따르므로 호출부에서 `text-*` 클래스로 지정한다.
 * stroke-width는 아이콘마다 프로토타입 값이 달라(1.9 / 2 / 2.4) 각 아이콘에 고정해 둔다.
 * 라벨 텍스트가 옆에 함께 오는 장식용이라 기본적으로 `aria-hidden` 처리한다.
 */

type IconProps = {
  /** 한 변 길이(px). 프로토타입 기준값을 아이콘별 기본값으로 둔다 */
  size?: number;
  className?: string;
};

/** 모든 아이콘이 공유하는 svg 속성 */
const svgProps = (size: number, strokeWidth: number, className?: string) => ({
  width: size,
  height: size,
  viewBox: "0 0 24 24",
  fill: "none" as const,
  stroke: "currentColor",
  strokeWidth,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
  className,
});

/* ---- 사이드바 내비게이션 (프로토타입 navIcon, 21px / stroke 1.9) ---- */

export function HomeIcon({ size = 21, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <path d="M4 11l8-6.5 8 6.5" />
      <path d="M6 9.5V20h12V9.5" />
    </svg>
  );
}

export function CardsIcon({ size = 21, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <rect x="4" y="4" width="16" height="16" rx="2.6" />
      <path d="M9 4v16" />
    </svg>
  );
}

export function GearIcon({ size = 21, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2.5v2.5M12 19v2.5M4.4 7l2.1 1.2M17.5 15.8l2.1 1.2M4.4 17l2.1-1.2M17.5 8.2l2.1-1.2" />
    </svg>
  );
}

/* ---- 목록 툴바 · 카드 메타 ---- */

export function SearchIcon({ size = 15, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <circle cx="11" cy="11" r="7" />
      <line x1="21" y1="21" x2="16.5" y2="16.5" />
    </svg>
  );
}

/** 정렬(양방향 화살표) */
export function SortIcon({ size = 14, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <path d="M7 4v16M4 7l3-3 3 3M17 20V4M14 17l3 3 3-3" />
    </svg>
  );
}

/** 리스트 보기(도트 + 선) */
export function ListIcon({ size = 17, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <line x1="8" y1="6" x2="20" y2="6" />
      <line x1="8" y1="12" x2="20" y2="12" />
      <line x1="8" y1="18" x2="20" y2="18" />
      <circle cx="3.5" cy="6" r="1.3" fill="currentColor" stroke="none" />
      <circle cx="3.5" cy="12" r="1.3" fill="currentColor" stroke="none" />
      <circle cx="3.5" cy="18" r="1.3" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function CalendarIcon({ size = 17, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <rect x="3.5" y="5" width="17" height="15" rx="2.5" />
      <path d="M3.5 9.5h17M8 3v4M16 3v4" />
    </svg>
  );
}

export function ClockIcon({ size = 15, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </svg>
  );
}

/** 문서/리포트 */
export function FileIcon({ size = 18, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <path d="M7 3h7l4 4v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
      <path d="M14 3v5h4" />
    </svg>
  );
}

/* ---- 셰브론 (월 이동 등, stroke 2.4) ---- */

export function ChevronLeftIcon({ size = 14, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2.4, className)}>
      <path d="M15 5l-7 7 7 7" />
    </svg>
  );
}

export function ChevronRightIcon({ size = 14, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2.4, className)}>
      <path d="M9 5l7 7-7 7" />
    </svg>
  );
}
