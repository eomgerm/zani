"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/domains/auth";
import {
  CreateSessionRequestError,
  createSession as createSessionApi,
  type CreatedSession,
  type SessionCreator,
} from "@/domains/lecture/infrastructure/createSessionApi";

/** 초대 코드를 눈으로 읽기 쉽게 네 글자씩 끊는다. 서버에 보낼 때는 원본을 쓴다. */
const formatInviteCode = (code: string) =>
  code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code;

/** 학생이 열게 되는 입장 화면 주소. 라우트는 app/prejoin/[inviteCode] 다. */
const inviteLinkOf = (code: string) =>
  typeof window === "undefined"
    ? `/prejoin/${code}`
    : `${window.location.origin}/prejoin/${code}`;

const messageFor = (error: unknown): string => {
  if (error instanceof CreateSessionRequestError) {
    if (error.code === "SESSION_APP_001") {
      return "이미 진행 중인 수업이 있어요. 먼저 종료한 뒤 새로 만들어 주세요.";
    }
    if (error.status === 400) {
      return "강의명을 확인해 주세요. 1자 이상 100자 이하여야 합니다.";
    }
    if (error.status === 401) {
      return "로그인이 필요해요. 다시 로그인해 주세요.";
    }
    if (error.status === 503) {
      return "서버가 잠시 바빠요. 잠시 후 다시 시도해 주세요.";
    }
  }
  return "방을 만들지 못했어요. 잠시 후 다시 시도해 주세요.";
};

/**
 * 강의실 만들기 설정. 강의명을 입력해 실제로 방을 만들고(POST /api/v1/sessions), 서버가 발급한 초대 링크를 공유한 뒤 강의실로 들어간다.
 *
 * <p>초대 코드는 서버가 만들기 때문에 방을 만들기 전에는 보여줄 수 없다. 그래서 생성 전에는 안내 문구만 두고, 생성 성공 후에 링크와 입장 버튼을 채운다.
 */
export function CreateSetupScreen({
  createSession = createSessionApi,
}: {
  /** 테스트에서 API 경계를 대체하기 위한 주입점. */
  createSession?: SessionCreator;
} = {}) {
  const router = useRouter();
  const { accessToken } = useAuth();
  const [title, setTitle] = useState("");
  const [copied, setCopied] = useState(false);
  const [created, setCreated] = useState<CreatedSession | null>(null);
  const [creating, setCreating] = useState(false);
  const [entering, setEntering] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const inviteLink = created === null ? null : inviteLinkOf(created.inviteCode);

  const copy = () => {
    if (inviteLink === null) return;
    navigator.clipboard?.writeText(inviteLink).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  const create = async () => {
    if (creating || accessToken === null) {
      if (accessToken === null) setError("로그인이 필요해요. 다시 로그인해 주세요.");
      return;
    }
    setCreating(true);
    setError(null);
    try {
      setCreated(await createSession(title.trim(), accessToken));
    } catch (caught) {
      setError(messageFor(caught));
    } finally {
      setCreating(false);
    }
  };

  /**
   * 강의실로 들어간다. 수업을 시작시키는 건 여기가 아니라 강의실이다.
   *
   * <p>여기서 시작하면 강사가 아직 LiveKit 에 연결되지 않은 상태에서 초대 코드가 열린다. 연결이 실패하면 학생만 강사 없는 방에 들어오게 된다. 그래서 시작은 강의실이 실제로 연결된 뒤에
   * 호출한다 — 연결이 실패하면 세션은 PREPARING 에 머물러 초대 코드가 열리지 않는다.
   */
  const enterRoom = () => {
    if (created === null || entering) {
      return;
    }
    setEntering(true);
    router.push(`/room/${created.sessionId}`);
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas p-6">
      <div className="w-full max-w-[560px]">
        <div className="mb-[18px] flex items-center gap-2.5">
          <Link
            href="/home"
            className="z-btn size-[38px] rounded-xl border border-line-muted bg-surface text-base text-ink"
          >
            ←
          </Link>
          <div>
            <div className="text-xl font-extrabold">강의실 만들기</div>
            <div className="text-[13px] text-ink-faint">강의 정보를 확인하고 방을 만들어 보세요.</div>
          </div>
        </div>

        <div className="flex flex-col gap-5 rounded-[22px] border border-line bg-surface px-7 py-[26px] shadow-soft">
          <div>
            <label htmlFor="lecture-title" className="mb-2 block text-[13px] font-bold text-ink-faint">
              강의명
            </label>
            <input
              id="lecture-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              disabled={created !== null}
              maxLength={100}
              placeholder="예) JavaScript 기초 1강"
              className="z-input px-[15px] py-[13px]"
            />
          </div>

          <div>
            <label className="mb-2 block text-[13px] font-bold text-ink-faint">강의 초대 링크</label>
            {created === null ? (
              <div className="rounded-xl border border-dashed border-line-light bg-muted-surface px-[15px] py-[13px] text-sm text-ink-ghost">
                방을 만들면 초대 링크가 발급돼요.
              </div>
            ) : (
              <>
                <div className="flex gap-2.5">
                  <div className="min-w-0 flex-1 truncate rounded-xl border border-line-light bg-muted-surface px-[15px] py-[13px] text-sm text-ink-muted">
                    {inviteLink}
                  </div>
                  <button
                    type="button"
                    onClick={copy}
                    className="z-btn whitespace-nowrap rounded-xl border border-line-primary bg-primary-soft px-5 py-[13px] text-sm text-primary"
                  >
                    {copied ? "복사됨" : "복사"}
                  </button>
                </div>
                <div className="mt-[7px] text-xs text-ink-ghost">
                  {created.status === "PREPARING"
                    ? "강의실에 들어가면 수업이 시작되고, 그때부터 이 링크로 참가자가 들어올 수 있어요. "
                    : "참가자에게 이 링크를 공유하면 강의실에 참여할 수 있어요. "}
                  코드로 직접 입력하려면{" "}
                  <b className="text-ink-muted">{formatInviteCode(created.inviteCode)}</b> 를 알려주세요.
                </div>
              </>
            )}
          </div>

          {error !== null && (
            <div
              role="alert"
              className="rounded-xl border border-line-light bg-muted-surface px-[15px] py-3 text-[13px] text-danger"
            >
              {error}
            </div>
          )}
        </div>

        <div className="mt-[18px] flex gap-3">
          <Link href="/home" className="z-btn z-btn-outline z-btn-lg flex-1">
            {created === null ? "취소" : "나중에 하기"}
          </Link>
          {created === null ? (
            <button
              type="button"
              onClick={create}
              disabled={creating || title.trim().length === 0}
              className="z-btn z-btn-primary z-btn-lg flex-[2] disabled:opacity-50"
            >
              {creating ? "만들고 있어요…" : "방 만들고 시작하기"}
            </button>
          ) : (
            <button
              type="button"
              onClick={enterRoom}
              disabled={entering}
              className="z-btn z-btn-primary z-btn-lg flex-[2] disabled:opacity-50"
            >
              {entering ? "들어가고 있어요…" : created.status === "PREPARING" ? "수업 시작하고 입장" : "강의실 입장"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
