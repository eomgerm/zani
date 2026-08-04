/**
 * ZANI 솔리드 픽토그램 세트(디자인 확정본, ZANI Icons.dc.html 카탈로그와 1:1).
 *
 * icons.tsx(선형·currentColor)와 달리 색이 시안에 박혀 있다 — 상태색(빨강 bell/warn,
 * 회색 muted 계열)이 의미를 가지므로 호출부에서 색을 바꾸지 않는다. 크기만 지정한다.
 * 라벨 텍스트가 옆에 함께 오는 장식용이라 기본적으로 `aria-hidden` 처리한다.
 */

type PictoProps = {
  /** 한 변 길이(px). 시안 기본값 22 */
  size?: number;
  className?: string;
};

/** 모든 픽토그램이 공유하는 svg 속성. 내부 도형 색은 각 아이콘에 고정되어 있다. */
const pictoProps = (size: number, className?: string) => ({
  width: size,
  height: size,
  viewBox: "0 0 24 24",
  fill: "none" as const,
  "aria-hidden": true,
  className,
});

export function PictoBars({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="3.6" y="13.2" width="4.6" height="7.2" rx="1.5" fill="#10b981" opacity=".45" />
      <rect x="9.7" y="8.6" width="4.6" height="11.8" rx="1.5" fill="#10b981" opacity=".7" />
      <rect x="15.8" y="3.6" width="4.6" height="16.8" rx="1.5" fill="#10b981" />
    </svg>
  );
}

export function PictoBell({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M12 2.8a6.4 6.4 0 0 1 6.4 6.4v3.4l1.9 3a1.1 1.1 0 0 1-.9 1.7H4.6a1.1 1.1 0 0 1-.9-1.7l1.9-3V9.2A6.4 6.4 0 0 1 12 2.8z"
        fill="#e0455f"
      />
      <rect x="9.6" y="18.8" width="4.8" height="2.6" rx="1.3" fill="#e0455f" />
    </svg>
  );
}

export function PictoBook({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path d="M3.6 5.2A2.2 2.2 0 0 1 5.8 3h5.2v18H5.8a2.2 2.2 0 0 1-2.2-2.2z" fill="#10b981" />
      <path
        d="M20.4 5.2A2.2 2.2 0 0 0 18.2 3H13v18h5.2a2.2 2.2 0 0 0 2.2-2.2z"
        fill="#10b981"
        opacity=".55"
      />
    </svg>
  );
}

export function PictoBookmark({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path d="M6 3.4h12a.8.8 0 0 1 .8.8V21l-6.8-4.6L5.2 21V4.2a.8.8 0 0 1 .8-.8z" fill="#ccd3df" />
    </svg>
  );
}

export function PictoBulb({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M12 2.6a7 7 0 0 1 3.9 12.8c-.6.4-.9 1-.9 1.7v.6H9v-.6c0-.7-.3-1.3-.9-1.7A7 7 0 0 1 12 2.6z"
        fill="#10b981"
      />
      <rect x="9.2" y="19.2" width="5.6" height="2.4" rx="1.2" fill="#10b981" opacity=".6" />
    </svg>
  );
}

export function PictoCalendar({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="2.8" y="4.2" width="18.4" height="17" rx="3.2" fill="#10b981" />
      <path d="M8 2.4v3.6M16 2.4v3.6" stroke="#0a7d57" strokeWidth="2.6" strokeLinecap="round" />
      <rect x="6.4" y="11.6" width="3.1" height="3.1" rx=".9" fill="#fff" />
      <rect x="10.9" y="11.6" width="3.1" height="3.1" rx=".9" fill="#fff" opacity=".75" />
      <rect x="15.4" y="11.6" width="3.1" height="3.1" rx=".9" fill="#fff" opacity=".5" />
      <rect x="6.4" y="16" width="3.1" height="3.1" rx=".9" fill="#fff" opacity=".75" />
      <rect x="10.9" y="16" width="3.1" height="3.1" rx=".9" fill="#fff" opacity=".5" />
    </svg>
  );
}

export function PictoCalendarMuted({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="3" y="4.4" width="18" height="16.2" rx="3" fill="#b9c1ce" />
      <rect x="6.4" y="11" width="3" height="3" rx=".8" fill="#fff" />
      <rect x="10.7" y="11" width="3" height="3" rx=".8" fill="#fff" opacity=".75" />
      <rect x="15" y="11" width="3" height="3" rx=".8" fill="#fff" opacity=".5" />
    </svg>
  );
}

export function PictoCamera({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="2.6" y="6.4" width="18.8" height="13.8" rx="3.2" fill="#10b981" />
      <rect x="8.2" y="3.4" width="7.6" height="4.4" rx="1.8" fill="#10b981" />
      <circle cx="12" cy="13.2" r="4.2" fill="#fff" />
      <circle cx="12" cy="13.2" r="2" fill="#10b981" />
    </svg>
  );
}

export function PictoCards({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="3.5" y="3.5" width="8" height="8" rx="2" fill="#10b981" />
      <rect x="12.9" y="3.5" width="7.6" height="8" rx="2" fill="#10b981" opacity=".55" />
      <rect x="3.5" y="12.9" width="8" height="7.6" rx="2" fill="#10b981" opacity=".55" />
      <rect x="12.9" y="12.9" width="7.6" height="7.6" rx="2" fill="#10b981" />
    </svg>
  );
}

export function PictoChat({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="2.8" y="3.4" width="18.4" height="13.8" rx="3.4" fill="#10b981" />
      <path d="M7.6 17v4l4.8-4z" fill="#10b981" />
      <rect x="6.8" y="7.2" width="10.4" height="2.1" rx="1.05" fill="#fff" />
      <rect x="6.8" y="11" width="6.6" height="2.1" rx="1.05" fill="#fff" opacity=".75" />
    </svg>
  );
}

export function PictoClipboard({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="4.6" y="3.8" width="14.8" height="17.6" rx="2.8" fill="#10b981" />
      <rect x="8.2" y="2" width="7.6" height="4.2" rx="1.8" fill="#0b8f66" />
      <rect x="8" y="9.6" width="8" height="2.1" rx="1.05" fill="#fff" />
      <rect x="8" y="13.4" width="5.6" height="2.1" rx="1.05" fill="#fff" opacity=".75" />
    </svg>
  );
}

export function PictoClock({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <circle cx="12" cy="12" r="9.5" fill="#10b981" />
      <path d="M12 6.8v5.2l3.6 2.1" stroke="#fff" strokeWidth="2.4" strokeLinecap="round" fill="none" />
    </svg>
  );
}

export function PictoClockMuted({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <circle cx="12" cy="12" r="9.5" fill="#b9c1ce" />
      <path d="M12 7v5.3l3.3 2" stroke="#fff" strokeWidth="2.4" strokeLinecap="round" fill="none" />
    </svg>
  );
}

export function PictoDoc({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="4.2" y="2.6" width="15.6" height="18.8" rx="2.8" fill="#10b981" />
      <rect x="7.8" y="7" width="8.4" height="2.2" rx="1.1" fill="#fff" />
      <rect x="7.8" y="11.2" width="8.4" height="2.2" rx="1.1" fill="#fff" opacity=".8" />
      <rect x="7.8" y="15.4" width="5.4" height="2.2" rx="1.1" fill="#fff" opacity=".6" />
    </svg>
  );
}

export function PictoDownload({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M12 3.2v9.3M7.4 9l4.6 4.6L16.6 9"
        stroke="#10b981"
        strokeWidth="2.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <rect x="4.5" y="18.6" width="15" height="2.6" rx="1.3" fill="#10b981" />
    </svg>
  );
}

export function PictoFlask({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M9.6 2.8h4.8a1 1 0 0 1 0 2h-.4v3.6l5 8.3a2 2 0 0 1-1.7 3H6.7a2 2 0 0 1-1.7-3l5-8.3V4.8h-.4a1 1 0 0 1 0-2z"
        fill="#10b981"
      />
      <circle cx="10.4" cy="15.6" r="1.2" fill="#fff" />
      <circle cx="13.8" cy="17.4" r=".9" fill="#fff" opacity=".8" />
    </svg>
  );
}

export function PictoGear({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M10.3 2.5h3.4l.5 2.4 2 .9 2.1-1.3 2.4 2.4-1.3 2.1.9 2 2.4.5v3.4l-2.4.5-.9 2 1.3 2.1-2.4 2.4-2.1-1.3-2 .9-.5 2.4h-3.4l-.5-2.4-2-.9-2.1 1.3-2.4-2.4 1.3-2.1-.9-2-2.4-.5v-3.4l2.4-.5.9-2-1.3-2.1 2.4-2.4 2.1 1.3 2-.9z"
        fill="#10b981"
      />
      <circle cx="12" cy="12" r="3.2" fill="#fff" />
    </svg>
  );
}

export function PictoGrid4({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="3.4" y="3.4" width="8.2" height="8.2" rx="2" fill="#10b981" />
      <rect x="12.9" y="3.4" width="7.7" height="8.2" rx="2" fill="#10b981" opacity=".55" />
      <rect x="3.4" y="12.9" width="8.2" height="7.7" rx="2" fill="#10b981" opacity=".55" />
      <rect x="12.9" y="12.9" width="7.7" height="7.7" rx="2" fill="#10b981" />
    </svg>
  );
}

export function PictoHome({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path d="M4 10.4L12 4l8 6.4V19a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z" fill="#10b981" />
      <rect x="9.7" y="13.6" width="4.6" height="7.4" rx="1.2" fill="#fff" />
    </svg>
  );
}

export function PictoInbox({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="3" y="4" width="18" height="16" rx="3" fill="#c9d1cc" />
      <path
        d="M3 13h5l1.6 2.4h4.8L16 13h5"
        stroke="#fff"
        strokeWidth="2"
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function PictoLines({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="3" y="4.6" width="14" height="2.7" rx="1.35" fill="#10b981" />
      <rect x="3" y="10.6" width="18" height="2.7" rx="1.35" fill="#10b981" opacity=".7" />
      <rect x="3" y="16.6" width="10" height="2.7" rx="1.35" fill="#10b981" opacity=".45" />
    </svg>
  );
}

export function PictoLink({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path d="M9.8 14.2l4.4-4.4" stroke="#10b981" strokeWidth="2.7" strokeLinecap="round" />
      <path
        d="M13.4 6.6l1.7-1.7a3.95 3.95 0 0 1 5.6 5.6L19 12.2"
        stroke="#10b981"
        strokeWidth="2.7"
        strokeLinecap="round"
        fill="none"
      />
      <path
        d="M10.6 17.4l-1.7 1.7a3.95 3.95 0 0 1-5.6-5.6L5 11.8"
        stroke="#10b981"
        strokeWidth="2.7"
        strokeLinecap="round"
        fill="none"
      />
    </svg>
  );
}

/**
 * 잠금. 시안 세트에 없어 같은 문법(솔리드 + 뮤티드 회색)으로 자체 제작했고, 디자인 오너
 * 확인을 거쳐 시안 카탈로그(ZANI Icons.dc.html)에도 추가됐다(2026-08-04).
 * 권한 없음 안내에 쓰므로 muted 계열(#b9c1ce)을 따른다.
 */
export function PictoLock({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M7.6 10V8.4a4.4 4.4 0 0 1 8.8 0V10"
        stroke="#b9c1ce"
        strokeWidth="2.6"
        strokeLinecap="round"
        fill="none"
      />
      <rect x="4.6" y="9.8" width="14.8" height="11.4" rx="2.8" fill="#b9c1ce" />
      <circle cx="12" cy="14.6" r="1.9" fill="#fff" />
      <rect x="11" y="15.4" width="2" height="3" rx="1" fill="#fff" />
    </svg>
  );
}

export function PictoPen({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path d="M14.5 4.9l4.6 4.6L8.6 20H4v-4.6z" fill="#10b981" />
      <path
        d="M16 3.4a2.3 2.3 0 0 1 3.2 0l1.4 1.4a2.3 2.3 0 0 1 0 3.2l-1.3 1.3-4.6-4.6z"
        fill="#10b981"
        opacity=".6"
      />
    </svg>
  );
}

export function PictoPeople({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <circle cx="8.3" cy="7.6" r="3.4" fill="#10b981" />
      <rect x="3.2" y="12.4" width="10.2" height="8.2" rx="3" fill="#10b981" />
      <circle cx="17" cy="8.4" r="2.7" fill="#10b981" opacity=".45" />
      <rect x="14" y="13.4" width="6.8" height="7.2" rx="2.6" fill="#10b981" opacity=".45" />
    </svg>
  );
}

export function PictoPin({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M12 21.6S5.4 15.8 5.4 10.9a6.6 6.6 0 1 1 13.2 0c0 4.9-6.6 10.7-6.6 10.7z"
        fill="#10b981"
      />
      <circle cx="12" cy="10.7" r="2.5" fill="#fff" />
    </svg>
  );
}

export function PictoPlus({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="2.8" y="2.8" width="18.4" height="18.4" rx="4.6" fill="#10b981" />
      <rect x="10.7" y="6.4" width="2.6" height="11.2" rx="1.3" fill="#fff" />
      <rect x="6.4" y="10.7" width="11.2" height="2.6" rx="1.3" fill="#fff" />
    </svg>
  );
}

export function PictoQbubble({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="2.8" y="3.4" width="18.4" height="13.8" rx="3.4" fill="#10b981" />
      <path d="M7.6 17v4l4.8-4z" fill="#10b981" />
      <path
        d="M9.9 8.3a2.2 2.2 0 1 1 3.2 2.4c-.6.3-1.1.8-1.1 1.4"
        stroke="#fff"
        strokeWidth="1.9"
        strokeLinecap="round"
        fill="none"
      />
      <circle cx="12" cy="14.4" r="1.15" fill="#fff" />
    </svg>
  );
}

export function PictoQuestion({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <circle cx="12" cy="12" r="9.5" fill="#10b981" />
      <path
        d="M9.4 9.4a2.7 2.7 0 1 1 3.9 3c-.7.4-1.3 1-1.3 1.8v.3"
        stroke="#fff"
        strokeWidth="2.1"
        strokeLinecap="round"
        fill="none"
      />
      <circle cx="12" cy="17.2" r="1.35" fill="#fff" />
    </svg>
  );
}

export function PictoSegs({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <rect x="2.8" y="9" width="5.6" height="6" rx="1.5" fill="#10b981" />
      <rect x="9.2" y="9" width="5.6" height="6" rx="1.5" fill="#10b981" opacity=".7" />
      <rect x="15.6" y="9" width="5.6" height="6" rx="1.5" fill="#10b981" opacity=".45" />
    </svg>
  );
}

export function PictoSpark({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path d="M12 2.6l2.4 7 7 2.4-7 2.4-2.4 7-2.4-7-7-2.4 7-2.4z" fill="#10b981" />
    </svg>
  );
}

export function PictoStar({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M12 2.8l2.9 5.8 6.4.9-4.6 4.5 1.1 6.4L12 17.4l-5.8 3 1.1-6.4-4.6-4.5 6.4-.9z"
        fill="#10b981"
      />
    </svg>
  );
}

export function PictoTarget({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <circle cx="12" cy="12" r="9.4" fill="#10b981" />
      <circle cx="12" cy="12" r="5.6" fill="#fff" />
      <circle cx="12" cy="12" r="2.5" fill="#10b981" />
    </svg>
  );
}

export function PictoWarn({ size = 22, className }: PictoProps) {
  return (
    <svg {...pictoProps(size, className)}>
      <path
        d="M13.4 3.6a1.6 1.6 0 0 0-2.8 0L2.5 18.2a1.6 1.6 0 0 0 1.4 2.4h16.2a1.6 1.6 0 0 0 1.4-2.4z"
        fill="#e0455f"
      />
      <rect x="10.9" y="8.6" width="2.2" height="6.2" rx="1.1" fill="#fff" />
      <circle cx="12" cy="17.2" r="1.35" fill="#fff" />
    </svg>
  );
}

/**
 * 카탈로그 이름 → 컴포넌트. fixture 등 데이터 파일이 JSX 없이 아이콘을 가리킬 때 쓴다.
 * 키는 시안 카탈로그(ZANI Icons.dc.html) 파일명과 같다.
 */
export const PICTOGRAMS = {
  bars: PictoBars,
  bell: PictoBell,
  book: PictoBook,
  bookmark: PictoBookmark,
  bulb: PictoBulb,
  calendar: PictoCalendar,
  calendarMuted: PictoCalendarMuted,
  camera: PictoCamera,
  cards: PictoCards,
  chat: PictoChat,
  clipboard: PictoClipboard,
  clock: PictoClock,
  clockMuted: PictoClockMuted,
  doc: PictoDoc,
  download: PictoDownload,
  flask: PictoFlask,
  gear: PictoGear,
  grid4: PictoGrid4,
  home: PictoHome,
  inbox: PictoInbox,
  lines: PictoLines,
  link: PictoLink,
  lock: PictoLock,
  pen: PictoPen,
  people: PictoPeople,
  pin: PictoPin,
  plus: PictoPlus,
  qbubble: PictoQbubble,
  question: PictoQuestion,
  segs: PictoSegs,
  spark: PictoSpark,
  star: PictoStar,
  target: PictoTarget,
  warn: PictoWarn,
} as const;

export type PictogramName = keyof typeof PICTOGRAMS;
