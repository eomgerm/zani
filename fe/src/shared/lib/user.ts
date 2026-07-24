/**
 * 프로토타입 시연용 목업 사용자.
 *
 * "use client" 모듈이 아닌 순수 데이터 모듈에 두어 서버/클라이언트 컴포넌트 양쪽에서
 * 동일한 값으로 import 되도록 한다.
 */
export const MOCK_USER = {
  name: "김도현",
  email: "dohyun.kim@gmail.com",
  initial: "김",
} as const;
