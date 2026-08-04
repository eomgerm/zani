import Image from "next/image";

/** 브랜드 로고 이미지 경로 (public/brand) */
export const LOGO_SRC = "/brand/zani-logo.png";

interface LogoProps {
  /** 로고 높이(px). 너비는 비율에 맞춰 자동 */
  height?: number;
}

/**
 * ZANI 브랜드 로고. 프로토타입의 워드마크 이미지를 그대로 사용한다.
 *
 * <p>비율은 이미지 실제 크기(938×349)에서 온다. 파일에 투명 여백이 있으면 지정한 height 를
 * 여백까지 나눠 쓰게 되어 워드마크가 그만큼 작게 찍힌다 — 이미지를 갈아끼울 때는 여백 없이
 * 자른 파일을 넣고 이 숫자도 함께 고쳐야 한다.
 */
export function Logo({ height = 44 }: LogoProps) {
  return (
    <Image
      src={LOGO_SRC}
      alt="ZANI"
      height={height}
      width={Math.round(height * (938 / 349))}
      priority
      className="block w-auto"
      style={{ height }}
    />
  );
}
