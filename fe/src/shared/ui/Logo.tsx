import Image from "next/image";

/** 브랜드 로고 이미지 경로 (public/brand) */
export const LOGO_SRC = "/brand/zani-logo.png";

interface LogoProps {
  /** 로고 높이(px). 너비는 비율에 맞춰 자동 */
  height?: number;
}

/**
 * ZANI 브랜드 로고. 프로토타입의 워드마크 이미지를 그대로 사용한다.
 * 밝은 배경 위에 얹히므로 multiply 블렌드로 배경을 자연스럽게 녹인다.
 */
export function Logo({ height = 44 }: LogoProps) {
  return (
    <Image
      src={LOGO_SRC}
      alt="ZANI"
      height={height}
      width={Math.round(height * (1745 / 900))}
      priority
      className="block w-auto mix-blend-multiply"
      style={{ height }}
    />
  );
}
