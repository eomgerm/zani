"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import type { ReactNode } from "react";

import {
  loginWithGoogle as requestGoogleLogin,
  type GoogleLoginRequester,
} from "../infrastructure/googleLoginApi";

export type AuthMember = {
  email: string;
  displayName: string;
  profileImageUrl: string | null;
};

export type AuthContextValue = {
  accessToken: string | null;
  member: AuthMember | null;
  isAuthenticated: boolean;
  loginWithGoogle: (idToken: string) => Promise<{ newMember: boolean }>;
  logout: () => void;
};

export type AuthProviderProps = {
  children: ReactNode;
  requestGoogleLoginFn?: GoogleLoginRequester;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children, requestGoogleLoginFn = requestGoogleLogin }: AuthProviderProps) {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [member, setMember] = useState<AuthMember | null>(null);

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
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ accessToken, member, isAuthenticated: accessToken !== null, loginWithGoogle, logout }),
    [accessToken, member, loginWithGoogle, logout],
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
