interface AvatarProps {
  /** 표시할 이니셜(예: 이름 첫 글자) */
  initial: string;
  /** 지름(px) */
  size?: number;
  /** 배경색. 참가자별로 런타임에 결정되므로 인라인으로 전달 */
  bg?: string;
  className?: string;
}

/**
 * 이니셜 아바타. 참가자 목록, 사이드바 프로필, 설정 화면 등에서 재사용한다.
 * 크기·배경색은 런타임 값이라 인라인으로 두고 나머지는 유틸리티 클래스로 처리한다.
 */
export function Avatar({ initial, size = 36, bg, className = "" }: AvatarProps) {
  return (
    <span
      className={`flex shrink-0 items-center justify-center rounded-full font-extrabold text-white ${className}`}
      style={{
        width: size,
        height: size,
        background: bg ?? "var(--color-primary)",
        fontSize: size * 0.42,
      }}
    >
      {initial}
    </span>
  );
}
