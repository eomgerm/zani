"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";

import {
  loginWithGoogle as requestGoogleLogin,
  type GoogleLoginRequester,
} from "../infrastructure/googleLoginApi";
import {
  refreshSession as requestRefreshSession,
  type RefreshSessionRequester,
} from "../infrastructure/refreshSessionApi";
import {
  getCurrentMember as requestCurrentMember,
  type CurrentMemberRequester,
} from "../infrastructure/getCurrentMemberApi";
import { logout as requestLogout, type LogoutRequester } from "../infrastructure/logoutApi";

export type AuthMember = {
  email: string;
  displayName: string;
  profileImageUrl: string | null;
};

export type AuthContextValue = {
  accessToken: string | null;
  member: AuthMember | null;
  isAuthenticated: boolean;
  /** 부트 시 세션 복원을 시도하는 동안 true. 이 동안은 로그인 여부를 아직 알 수 없다. */
  isInitializing: boolean;
  loginWithGoogle: (idToken: string) => Promise<{ newMember: boolean }>;
  logout: () => void;
  /**
   * 이름 변경이 서버에 반영된 뒤 세션의 회원 정보를 맞춘다 — 사이드바·아바타가 새로고침 없이 새 이름을 쓴다.
   * 서버에 요청하지 않는다. 저장에 성공한 쪽에서만 부른다.
   */
  applyDisplayName: (displayName: string) => void;
};

export type AuthProviderProps = {
  children: ReactNode;
  requestGoogleLoginFn?: GoogleLoginRequester;
  requestRefreshSessionFn?: RefreshSessionRequester;
  requestCurrentMemberFn?: CurrentMemberRequester;
  requestLogoutFn?: LogoutRequester;
};

const AuthContext = createContext<AuthContextValue | null>(null);

/** Access Token 만료 이만큼 전에 미리 갱신해, 사용 중인 요청이 401 을 맞는 일을 막는다. */
const RENEW_BEFORE_EXPIRY_MS = 60_000;

export function AuthProvider({
  children,
  requestGoogleLoginFn = requestGoogleLogin,
  requestRefreshSessionFn = requestRefreshSession,
  requestCurrentMemberFn = requestCurrentMember,
  requestLogoutFn = requestLogout,
}: AuthProviderProps) {
  // 세션 복원을 페이지 로드당 한 번으로 묶는 표시. StrictMode 가 effect 를 다시 실행해도 같은 컴포넌트
  // 인스턴스라 ref 는 유지되므로, 두 번째 실행을 여기서 걸러낼 수 있다.
  const restoreStartedRef = useRef(false);
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [accessTokenExpiresAt, setAccessTokenExpiresAt] = useState<string | null>(null);
  const [member, setMember] = useState<AuthMember | null>(null);
  const [isInitializing, setIsInitializing] = useState(true);

  // 앱 부트 시 1회, HttpOnly refresh 쿠키로 세션 복원을 시도한다. 로그인한 적 없거나
  // 세션이 만료된 경우 실패하는 게 정상이라 조용히 로그아웃 상태를 유지한다.
  useEffect(() => {
    // refresh 토큰은 1회용이다(서버가 rotate 하며 이전 토큰을 폐기한다). 그래서 이 복원 요청은
    // 페이지 로드당 정확히 한 번만 나가야 한다. StrictMode 는 개발 모드에서 effect 를 두 번 실행하는데,
    // 그러면 같은 쿠키로 두 번 호출해 첫 요청이 토큰을 회전시키고 두 번째가 거부된다 — 로그인 직후
    // 새로고침하면 항상 로그아웃되던 원인이다. 그 두 번째 호출을 여기서 막는다.
    if (restoreStartedRef.current) {
      return;
    }
    restoreStartedRef.current = true;

    (async () => {
      try {
        const session = await requestRefreshSessionFn();
        const currentMember = await requestCurrentMemberFn(session.accessToken);
        setAccessToken(session.accessToken);
        setAccessTokenExpiresAt(session.accessTokenExpiresAt);
        setMember(currentMember);
      } catch {
        // 세션 없음/만료 — 로그아웃 상태를 유지한다.
      } finally {
        setIsInitializing(false);
      }
    })();
    // 부트 시 한 번만 시도한다. 함수 identity 변화로 재시도하지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Access Token 은 1시간짜리라, 만료 직전에 refresh 쿠키로 미리 갱신해 사용 중 401 을 막는다.
  // 갱신 실패는 세션 만료로 보고 로그아웃 상태로 되돌린다 — 인증 가드가 로그인 화면으로 보낸다.
  useEffect(() => {
    if (accessToken === null || accessTokenExpiresAt === null) return;

    let cancelled = false;
    const delay = Math.max(Date.parse(accessTokenExpiresAt) - Date.now() - RENEW_BEFORE_EXPIRY_MS, 0);
    const timer = setTimeout(async () => {
      try {
        const session = await requestRefreshSessionFn();
        if (cancelled) return;
        setAccessToken(session.accessToken);
        setAccessTokenExpiresAt(session.accessTokenExpiresAt);
      } catch {
        if (cancelled) return;
        setAccessToken(null);
        setAccessTokenExpiresAt(null);
        setMember(null);
      }
    }, delay);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [accessToken, accessTokenExpiresAt, requestRefreshSessionFn]);

  const loginWithGoogle = useCallback(
    async (idToken: string) => {
      const result = await requestGoogleLoginFn(idToken);
      setAccessToken(result.accessToken);
      setAccessTokenExpiresAt(result.accessTokenExpiresAt);
      setMember({
        email: result.email,
        displayName: result.displayName,
        profileImageUrl: result.profileImageUrl,
      });
      return { newMember: result.newMember };
    },
    [requestGoogleLoginFn],
  );

  const logout = useCallback(() => {
    setAccessToken(null);
    setAccessTokenExpiresAt(null);
    setMember(null);
    requestLogoutFn().catch(() => {
      // 서버 로그아웃 실패는 조용히 무시한다 — 로컬 상태는 이미 정리됐고 수업/화면 흐름을 막지 않는다.
    });
  }, [requestLogoutFn]);

  const applyDisplayName = useCallback((displayName: string) => {
    setMember((current) => (current === null ? null : { ...current, displayName }));
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      accessToken,
      member,
      isAuthenticated: accessToken !== null,
      isInitializing,
      loginWithGoogle,
      logout,
      applyDisplayName,
    }),
    [accessToken, member, isInitializing, loginWithGoogle, logout, applyDisplayName],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context === null) {
    throw new Error("useAuth must be used within an AuthProvider.");
  }

  return context;
}
