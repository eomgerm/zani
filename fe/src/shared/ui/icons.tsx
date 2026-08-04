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

/* 사이드바 내비게이션 아이콘은 pictograms 로 옮겼다 — 시안이 같은 도형을 상태별 색으로 쓴다. */

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

/** 섹션 제목 앞 햄버거(마지막 줄이 짧다) */
export function MenuIcon({ size = 16, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <path d="M4 6h16M4 12h16M4 18h10" />
    </svg>
  );
}

export function BookmarkIcon({ size = 15, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <path d="M6 4h12v16l-6-4-6 4z" />
    </svg>
  );
}

export function DownloadIcon({ size = 16, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2.2, className)}>
      <path d="M12 4v11M7 11l5 4 5-4M5 20h14" />
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

/* ---- 강의실 (프로토타입 SC-09, 22px / stroke 1.9) ---- */

export function PeopleIcon({ size = 22, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <circle cx="9" cy="7" r="3.2" />
      <path d="M3.5 19a5.5 5.5 0 0 1 11 0M16 8h5M16 12h5M16 16h5" />
    </svg>
  );
}

export function ChatIcon({ size = 22, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <path d="M20 11.5a7.5 7.5 0 0 1-10.5 6.9L4 20l1.6-4.5A7.5 7.5 0 1 1 20 11.5Z" />
    </svg>
  );
}

export function MicIcon({ size = 22, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <rect x="9" y="2" width="6" height="12" rx="3" />
      <path d="M5 10a7 7 0 0 0 14 0M12 19v3" />
    </svg>
  );
}

/**
 * 마이크 꺼짐(대각선 슬래시). 색은 다른 아이콘처럼 호출부가 정한다 —
 * 타일 이름칩은 빨간색으로, 붉은 배경의 컨트롤 버튼은 흰색으로 쓴다(티켓 246).
 */
export function MicOffIcon({ size = 11, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2.4, className)}>
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
      <path d="M4 3l16 18" />
    </svg>
  );
}

export function CameraIcon({ size = 22, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <path d="M15 10l6-3.5v11L15 14M3 6.5h10a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1Z" />
    </svg>
  );
}

/** 카메라 꺼짐(대각선 슬래시). MicOffIcon과 같은 규칙 — 색은 호출부가 정한다. */
export function CameraOffIcon({ size = 11, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2, className)}>
      <path d="M15 10l6-3.5v11L15 14M3 6.5h10a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1Z" />
      <path d="M4 3l16 18" />
    </svg>
  );
}

export function ScreenShareIcon({ size = 22, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <rect x="3" y="4" width="18" height="13" rx="2" />
      <path d="M12 21v-3M8 21h8M12 13V8m0 0-2.2 2.2M12 8l2.2 2.2" />
    </svg>
  );
}

/** 손들기. 이모지는 OS·폰트에 따라 컬러 이모지로도 렌더돼 다른 아이콘과 어긋나므로 SVG로 고정한다. */
export function HandIcon({ size = 22, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <path d="M7 11V6a1.5 1.5 0 0 1 3 0v4M10 10V4.5a1.5 1.5 0 0 1 3 0V10M13 10V6a1.5 1.5 0 0 1 3 0v5M16 8.5a1.5 1.5 0 0 1 3 0v4.5a7 7 0 0 1-7 7h-1a6 6 0 0 1-5.2-3L4 15.5c-.6-1 .3-2.2 1.4-1.9L7 14" />
    </svg>
  );
}

export function ReactionIcon({ size = 22, className }: IconProps) {
  return (
    <svg {...svgProps(size, 1.9, className)}>
      <circle cx="12" cy="12" r="9" />
      <path d="M8.5 14.5a4 4 0 0 0 7 0M9 9.5h.01M15 9.5h.01" />
    </svg>
  );
}

/** 나가기(프로토타입은 stroke 2.2) */
export function CloseIcon({ size = 22, className }: IconProps) {
  return (
    <svg {...svgProps(size, 2.2, className)}>
      <path d="M6 6l12 12M18 6 6 18" />
    </svg>
  );
}

/* ---- 셰브론 (월 이동 · 갤러리 페이지 이동, stroke 2.4) ---- */

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
