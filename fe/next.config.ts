import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  turbopack: {
    root: __dirname,
  },
  async rewrites() {
    // 배포 환경은 Nginx가 같은 도메인에서 /api를 백엔드로 라우팅한다.
    // 로컬 개발 서버에는 그 프록시가 없으므로 개발 모드에서만 직접 연결한다.
    if (process.env.NODE_ENV !== "development") {
      return [];
    }

    return [
      {
        source: "/api/:path*",
        destination: "http://localhost:8080/api/:path*",
      },
    ];
  },
};

export default nextConfig;
