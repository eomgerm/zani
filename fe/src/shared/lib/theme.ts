/**
 * ZANI 프로토타입 디자인 토큰 (그린 테마).
 *
 * 프로토타입은 Tailwind 표준 팔레트가 아닌 고정 색상 값을 사용한다.
 * 화면을 그대로 옮기기 위해 색·그림자·그라데이션을 이곳에 모아두고,
 * 각 화면과 shared/ui 컴포넌트는 이 토큰만 참조한다.
 */

export const color = {
  // brand (green)
  primary: "#1cdd93",
  primaryDark: "#19c986",
  primaryDeep: "#16c582",
  primaryAlt: "#21e298",
  primarySoft: "#e9f8f2",
  primarySofter: "#f0f8f5",

  // surfaces
  surface: "#ffffff",
  bg: "#f4f7f6",
  bgMint: "#ecf5f1",
  bgMintDeep: "#e8f2ee",
  surfaceFaint: "#fbfbfe",
  surfaceMuted: "#f5f8f7",

  // text
  text: "#2c3049",
  textMuted: "#6a7096",
  textFaint: "#8a90b4",
  textFainter: "#9aa0c2",
  textDisabled: "#b0b5d2",
  textSub: "#5a5f7d",
  textLabel: "#4a4f6d",

  // borders
  border: "#e6efeb",
  borderMuted: "#dfebe7",
  borderMint: "#e8f2ee",
  borderLight: "#edf2f0",

  // accents
  red: "#e0455f",
  redSoft: "#ffe7ea",
  amber: "#f4c325",
  amberText: "#b78f0c",
  amberSoft: "#fdf6df",
  pink: "#f26d7d",

  // dark surfaces (room stage / video editor)
  ink: "#0a0b10",
  inkPanel: "#12152a",
  inkPanel2: "#1a1e35",
  inkBorder: "#262b46",
  inkBorderSoft: "#3a4066",
  inkText: "#eef0fa",
  inkTextMuted: "#8b93bf",
  inkTextFaint: "#c7ccf0",
  roomPanel: "#12152a",
  roomBorder: "#2a2e48",
  roomInput: "#14162a",
  roomBorderSoft: "#34395a",
  purple: "#7c6bf0",
} as const;

export const gradient = {
  /** 로그인 배경 */
  loginBg: "linear-gradient(120deg,#f5f6fc 0%,#e9f8f2 45%,#edfaf5 100%)",
  /** 로그인 워드마크 텍스트 그라데이션 */
  brandText: "linear-gradient(120deg,#42daa0,#16b276)",
  /** 카드 아이콘 배경 */
  iconMint: "linear-gradient(135deg,#daf3ea,#c9ecdf)",
  /** 집중도 범례 */
  focusScale: "linear-gradient(90deg,#e0455f,#f4c325,#16c582)",
} as const;

export const shadow = {
  card: "0 4px 22px rgba(24,74,62,.05)",
  soft: "0 12px 40px rgba(24,74,62,.07)",
  pop: "0 12px 34px rgba(24,74,62,.16)",
} as const;

export const radius = {
  card: 20,
  panel: 18,
  control: 14,
  pill: 999,
} as const;

/** 브랜드 로고 이미지 경로 (public/brand) */
export const LOGO_SRC = "/brand/zani-logo.png";
