import type { CSSProperties } from "react";
import { color } from "@/shared/lib/theme";

interface AvatarProps {
  /** 표시할 이니셜(예: 이름 첫 글자) */
  initial: string;
  /** 지름(px) */
  size?: number;
  /** 배경색. 미지정 시 브랜드 그린 */
  bg?: string;
  style?: CSSProperties;
}

/**
 * 이니셜 아바타. 참가자 목록, 사이드바 프로필, 설정 화면 등에서 재사용한다.
 */
export function Avatar({ initial, size = 36, bg, style }: AvatarProps) {
  return (
    <span
      style={{
        width: size,
        height: size,
        borderRadius: "50%",
        background: bg ?? color.primary,
        color: "#fff",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontWeight: 800,
        fontSize: size * 0.42,
        flexShrink: 0,
        ...style,
      }}
    >
      {initial}
    </span>
  );
}
