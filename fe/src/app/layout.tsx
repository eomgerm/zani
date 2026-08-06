import type { Metadata } from "next";
import { Noto_Sans_KR, Noto_Sans_Mono } from "next/font/google";
import { AuthProvider } from "@/domains/auth";
import "./globals.css";

/*
 * 본문 서체는 Noto Sans KR 하나다.
 *
 * 앞서 라틴을 Space Grotesk 로 먼저 받고 한글만 Noto 로 넘겼는데, 그러면 한 문장 안에서 라틴과 한글이
 * 서로 다른 서체로 그려져 굵기와 x-height 가 어긋난다. UI 가 거의 전부 한글이라 얻는 것보다 어긋나는
 * 쪽이 크다.
 *
 * 웨이트를 나열하지 않고 가변 폰트로 받는다. 화면 코드는 font-extrabold(800) 를 94곳,
 * font-black(900) 을 9곳에서 쓰는데 고정 웨이트로 받으면 그 둘이 없어 브라우저가 가짜 굵게로 그린다.
 * 한글은 획이 많아 받침 있는 음절에서 획이 서로 먹는다. Noto Sans KR 가변은 100–900 을 다 준다.
 */
const notoSansKr = Noto_Sans_KR({
  variable: "--font-noto-sans-kr",
  subsets: ["latin"],
});

/*
 * 시각·수치 표기 전용(font-mono). 자릿수가 바뀌어도 폭이 흔들리지 않아야 하는 자리에만 쓴다 —
 * 카운트다운, 타임스탬프, 페이지 번호. 그 자리에는 한글이 한 글자도 없다.
 *
 * 같은 Noto 슈퍼패밀리라 본문과 획 굵기·x-height 가 맞고, 가변이라 100–900 을 다 준다. 앞서 쓰던
 * Space Mono 는 400/700 뿐이라 이 자리 중 font-extrabold·font-black 인 여섯 곳이 가짜 굵게였다.
 */
const notoSansMono = Noto_Sans_Mono({
  variable: "--font-noto-sans-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "ZANI",
  description: "AI 기반 실시간 학습 반응 분석 및 맞춤 복습 서비스",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="ko"
      className={`${notoSansKr.variable} ${notoSansMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
