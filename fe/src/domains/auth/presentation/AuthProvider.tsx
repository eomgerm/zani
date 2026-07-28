"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
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
};

export type AuthProviderProps = {
  children: ReactNode;
  requestGoogleLoginFn?: GoogleLoginRequester;
  requestRefreshSessionFn?: RefreshSessionRequester;
  requestCurrentMemberFn?: CurrentMemberRequester;
  requestLogoutFn?: LogoutRequester;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({
  children,
  requestGoogleLoginFn = requestGoogleLogin,
  requestRefreshSessionFn = requestRefreshSession,
  requestCurrentMemberFn = requestCurrentMember,
  requestLogoutFn = requestLogout,
}: AuthProviderProps) {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [member, setMember] = useState<AuthMember | null>(null);
  const [isInitializing, setIsInitializing] = useState(true);

  // 앱 부트 시 1회, HttpOnly refresh 쿠키로 세션 복원을 시도한다. 로그인한 적 없거나
  // 세션이 만료된 경우 실패하는 게 정상이라 조용히 로그아웃 상태를 유지한다.
  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const session = await requestRefreshSessionFn();
        if (cancelled) return;
        const currentMember = await requestCurrentMemberFn(session.accessToken);
        if (cancelled) return;
        setAccessToken(session.accessToken);
        setMember(currentMember);
      } catch {
        // 세션 없음/만료 — 로그아웃 상태를 유지한다.
      } finally {
        if (!cancelled) setIsInitializing(false);
      }
    })();

    return () => {
      cancelled = true;
    };
    // 부트 시 한 번만 시도한다. 함수 identity 변화로 재시도하지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loginWithGoogle = useCallback(
    async (idToken: string) => {
      const result = await requestGoogleLoginFn(idToken);
      setAccessToken(result.accessToken);
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
    setMember(null);
    requestLogoutFn().catch(() => {
      // 서버 로그아웃 실패는 조용히 무시한다 — 로컬 상태는 이미 정리됐고 수업/화면 흐름을 막지 않는다.
    });
  }, [requestLogoutFn]);

  const value = useMemo<AuthContextValue>(
    () => ({
      accessToken,
      member,
      isAuthenticated: accessToken !== null,
      isInitializing,
      loginWithGoogle,
      logout,
    }),
    [accessToken, member, isInitializing, loginWithGoogle, logout],
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
