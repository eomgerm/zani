"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/domains/auth";
import {
  finalizeNote,
  InstructorNoteRequestError,
  NOTE_ALREADY_FINALIZED,
  NOTE_CONTENT_TOO_LONG,
  saveNoteDraft,
  SESSION_STILL_LIVE,
  type NoteDraftSaver,
  type NoteFinalizer,
} from "../infrastructure/instructorNoteApi";

/**
 * 수업 종료 후 강사 사후 메모 에디터. 메모는 수업 하나에 하나이며 구간으로 나누지 않는다.
 *
 * 입력을 멈추면 초안을 자동 저장하고, 저장될 때마다 서버의 30분 비활성 타이머가
 * 초기화됨을 안내한다. `작성 완료`를 누르면 확정한 뒤 곧바로 내 강의실로 이동한다.
 * 30분 비활성 자동 확정처럼 사용자가 완료를 누르지 않은 경우에만 편집 불가 상태(읽기 전용)를
 * 보여준다(수정·재생성 없음). 저장된 본문을 되돌려주는 조회 API 가 없으므로 새로고침하면
 * 입력하던 내용은 사라진다.
 */

const AUTO_SAVE_DELAY_MS = 1_000;
const CONTENT_MAX_LENGTH = 5_000;

const MESSAGES = {
  signedOut: "로그인이 풀렸습니다. 다시 로그인한 뒤 작성해 주세요.",
  tooLong: "메모는 5,000자까지 저장할 수 있습니다. 내용을 줄인 뒤 다시 입력해 주세요.",
  sessionLive: "아직 진행 중인 수업입니다. 수업이 종료된 뒤에 메모를 작성할 수 있습니다.",
  instructorOnly: "이 수업의 강사만 사후 메모를 작성할 수 있습니다.",
  notFound: "수업을 찾을 수 없습니다.",
  saveFailed: "자동 저장에 실패했습니다. 입력을 이어가면 다시 시도합니다.",
  finalizeFailed: "작성 완료에 실패했습니다. 잠시 후 다시 시도해 주세요.",
  analysisDelayed: "사후 분석 준비가 지연되고 있습니다. 잠시 후 다시 시도해 주세요.",
} as const;

type NoteFailure = { kind: "finalized" } | { kind: "message"; text: string };

/** 같은 409 라도 자동 확정(NOTE_002)과 진행 중 수업(SESSION_APP_009)은 다르게 처리해야 한다. */
function classifyFailure(error: unknown, fallback: string): NoteFailure {
  if (!(error instanceof InstructorNoteRequestError)) {
    return { kind: "message", text: fallback };
  }
  if (error.code === NOTE_ALREADY_FINALIZED) return { kind: "finalized" };
  if (error.code === SESSION_STILL_LIVE) return { kind: "message", text: MESSAGES.sessionLive };
  if (error.code === NOTE_CONTENT_TOO_LONG || error.status === 400) {
    return { kind: "message", text: MESSAGES.tooLong };
  }
  if (error.status === 401) return { kind: "message", text: MESSAGES.signedOut };
  if (error.status === 403) return { kind: "message", text: MESSAGES.instructorOnly };
  if (error.status === 404) return { kind: "message", text: MESSAGES.notFound };
  if (error.status === 503) return { kind: "message", text: MESSAGES.analysisDelayed };
  return { kind: "message", text: fallback };
}

function timeOf(instant: string): string {
  const date = new Date(instant);
  const hours = String(date.getHours()).padStart(2, "0");
  const minutes = String(date.getMinutes()).padStart(2, "0");
  return `${hours}:${minutes}`;
}

type InstructorNoteEditorProps = {
  sessionId: string;
  /** 초안 저장 어댑터. 테스트에서 대체한다. */
  saveDraftRequest?: NoteDraftSaver;
  /** 확정 어댑터. 테스트에서 대체한다. */
  finalizeRequest?: NoteFinalizer;
  /** 마지막 입력 후 자동 저장까지의 대기 시간. */
  autoSaveDelayMs?: number;
};

export function InstructorNoteEditor({
  sessionId,
  saveDraftRequest = saveNoteDraft,
  finalizeRequest = finalizeNote,
  autoSaveDelayMs = AUTO_SAVE_DELAY_MS,
}: InstructorNoteEditorProps) {
  const router = useRouter();
  const { accessToken, isInitializing } = useAuth();

  const [content, setContent] = useState("");
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [finalizing, setFinalizing] = useState(false);
  const [finalized, setFinalized] = useState(false);
  const [autoFinalized, setAutoFinalized] = useState(false);
  const [finalizedContent, setFinalizedContent] = useState("");

  // 자동 저장은 타이머·비동기 완료 시점에 최신 값을 읽어야 하므로 ref 로 함께 관리한다.
  const contentRef = useRef("");
  const lastSavedRef = useRef<string | null>(null);
  const savingRef = useRef(false);
  const finalizedRef = useRef(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const disposedRef = useRef(false);

  useEffect(
    () => () => {
      disposedRef.current = true;
      if (timerRef.current !== null) clearTimeout(timerRef.current);
      abortRef.current?.abort();
    },
    [],
  );

  const clearPendingSave = () => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  const markFinalized = (shownContent: string, auto: boolean) => {
    finalizedRef.current = true;
    clearPendingSave();
    setFinalized(true);
    setAutoFinalized(auto);
    setFinalizedContent(shownContent);
    setConfirmOpen(false);
    setErrorMessage(null);
  };

  const runSave = async () => {
    if (savingRef.current || finalizedRef.current || disposedRef.current) return;
    if (!accessToken) {
      setErrorMessage(MESSAGES.signedOut);
      return;
    }
    const snapshot = contentRef.current;
    if (snapshot === lastSavedRef.current) return;

    savingRef.current = true;
    setSaving(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const result = await saveDraftRequest(sessionId, snapshot, accessToken, controller.signal);
      if (disposedRef.current) return;
      lastSavedRef.current = snapshot;
      setSavedAt(result.updatedAt);
      setErrorMessage(null);
    } catch (error) {
      if (disposedRef.current) return;
      const failure = classifyFailure(error, MESSAGES.saveFailed);
      if (failure.kind === "finalized") {
        // 30분 비활성 자동 확정 뒤의 저장 시도. 서버에는 마지막 저장분까지만 반영됐다.
        markFinalized(lastSavedRef.current ?? "", true);
      } else {
        setErrorMessage(failure.text);
      }
    } finally {
      savingRef.current = false;
      if (!disposedRef.current) {
        setSaving(false);
        // 저장하는 동안 입력이 이어졌으면 최신 내용을 곧바로 이어서 저장한다.
        if (!finalizedRef.current && contentRef.current !== snapshot) {
          scheduleSave();
        }
      }
    }
  };

  const scheduleSave = () => {
    clearPendingSave();
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      void runSave();
    }, autoSaveDelayMs);
  };

  const handleChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    const next = event.target.value;
    contentRef.current = next;
    setContent(next);
    scheduleSave();
  };

  const confirmFinalize = async () => {
    if (finalizing || finalizedRef.current) return;
    if (!accessToken) {
      setConfirmOpen(false);
      setErrorMessage(MESSAGES.signedOut);
      return;
    }
    setFinalizing(true);
    clearPendingSave();
    try {
      // finalize 는 본문을 받지 않으므로, 아직 저장 전인 입력이 있으면 확정 전에 반영한다.
      if (contentRef.current !== (lastSavedRef.current ?? "")) {
        await saveDraftRequest(sessionId, contentRef.current, accessToken);
        lastSavedRef.current = contentRef.current;
      }
      await finalizeRequest(sessionId, accessToken);
      // 작성을 완료하면 확정 화면을 따로 보여주지 않고 곧바로 내 강의실로 이동한다.
      // 확정 뒤에는 되돌릴 수 없으므로, 남은 자동 저장을 멈추고 이동만 한다.
      finalizedRef.current = true;
      clearPendingSave();
      setConfirmOpen(false);
      router.push("/my-lectures");
    } catch (error) {
      const failure = classifyFailure(error, MESSAGES.finalizeFailed);
      if (failure.kind === "finalized") {
        markFinalized(lastSavedRef.current ?? "", true);
      } else {
        setConfirmOpen(false);
        setErrorMessage(failure.text);
      }
    } finally {
      setFinalizing(false);
    }
  };

  if (isInitializing) return null;

  const isEmpty = !content.trim();
  const overLimit = content.length > CONTENT_MAX_LENGTH;
  const savedTime = savedAt ? timeOf(savedAt) : null;

  if (!accessToken) {
    return (
      <div className="min-h-screen bg-canvas px-6 py-8">
        <div className="mx-auto max-w-[760px]">
          <div className="z-card-lg px-8 py-[30px]" data-testid="note-signed-out">
            <h1 className="mb-1.5 text-2xl font-extrabold tracking-[-.4px]">로그인이 필요합니다</h1>
            <p className="mb-[22px] text-sm leading-[1.7] text-ink-sub">
              사후 메모는 수업을 진행한 강사만 작성할 수 있습니다. 다시 로그인해 주세요.
            </p>
            <Link href="/login" className="z-btn z-btn-primary z-btn-md no-underline">
              로그인하러 가기
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-canvas px-6 py-8">
      <div className="mx-auto max-w-[760px]">
        <Link
          href="/my-lectures"
          className="mb-4 inline-flex items-center gap-1.5 text-sm font-bold text-ink-faint no-underline"
        >
          ← 내 강의실로 돌아가기
        </Link>

        <div className="z-card-lg px-8 py-[30px]" data-testid="instructor-note-editor">
          <div className="z-pill mb-4 bg-warn-soft px-3 py-[5px] text-[12.5px] text-warn-text">
            사후 메모 작성
          </div>
          <h1 className="mb-1.5 text-2xl font-extrabold tracking-[-.4px]">수업 사후 메모</h1>
          <p className="mb-3.5 text-sm leading-[1.7] text-ink-sub">
            수업에서 다룬 개념명, 강조한 이유, 학생이 다시 볼 포인트를 자유 형식으로 작성해
            주세요. 메모는 수업당 하나이며 AI 분석에 참고됩니다.
          </p>

          {finalized ? (
            <>
              {autoFinalized && (
                <div
                  data-testid="note-auto-finalized-notice"
                  className="mb-3.5 rounded-[12px] bg-warn-soft px-4 py-3 text-[13px] leading-[1.6] text-warn-text"
                >
                  30분 동안 저장된 입력이 없어 메모가 자동으로 확정되었습니다. 마지막으로 저장된
                  내용까지만 반영되었습니다.
                </div>
              )}
              <div
                data-testid="note-finalized-content"
                className="z-field-readonly min-h-[260px] whitespace-pre-wrap rounded-[14px] px-5 py-[18px] text-sm leading-[1.7]"
              >
                {finalizedContent || <span className="text-ink-faint">작성된 메모 없음</span>}
              </div>
              <p
                data-testid="note-finalized-notice"
                className="mt-3 text-[13px] leading-[1.6] text-ink-muted"
              >
                메모 작성이 완료되었습니다. 확정된 메모는 수정하거나 다시 생성할 수 없으며, 사후
                분석이 곧 시작됩니다.
              </p>
              <div className="mt-[18px] flex items-center gap-3">
                <div className="flex-1" />
                <button
                  onClick={() => router.push("/my-lectures")}
                  className="z-btn z-btn-primary z-btn-lg whitespace-nowrap"
                  data-testid="note-go-lectures"
                >
                  내 강의실로 이동
                </button>
              </div>
            </>
          ) : (
            <>
              <textarea
                value={content}
                onChange={handleChange}
                disabled={finalizing}
                placeholder="예) Context 리렌더링 파트에서 학생들이 많이 헷갈려 했다. 다음 시간에 Provider value 참조 개념을 예시로 다시 짚어주면 좋겠다."
                className="z-input z-textarea min-h-[260px] rounded-[14px] bg-faint px-5 py-[18px]"
                data-testid="note-textarea"
              />
              <div className="mt-2.5 flex items-start justify-between gap-3 text-[12.5px]">
                <p className="leading-[1.6] text-ink-faint" data-testid="note-save-status" aria-live="polite">
                  {saving
                    ? "저장 중…"
                    : savedTime
                      ? `${savedTime}에 저장됨 · 30분 비활성 타이머가 초기화되었습니다`
                      : "입력을 멈추면 자동으로 저장됩니다"}
                </p>
                <p
                  className={overLimit ? "font-bold text-danger" : "text-ink-faint"}
                  data-testid="note-char-count"
                >
                  {content.length.toLocaleString("ko-KR")} / 5,000자
                </p>
              </div>
              <p className="mt-1 text-[12.5px] leading-[1.6] text-ink-faint">
                저장될 때마다 30분 비활성 타이머가 초기화되며, 30분 동안 저장이 없으면 메모가
                자동으로 확정됩니다.
              </p>
              {errorMessage && (
                <div
                  data-testid="note-error"
                  className="mt-3 rounded-[12px] bg-danger-soft px-4 py-3 text-[13px] font-semibold text-danger"
                >
                  {errorMessage}
                </div>
              )}
              <div className="mt-[18px] flex items-center gap-3">
                <div className="flex-1" />
                <button
                  onClick={() => setConfirmOpen(true)}
                  disabled={finalizing}
                  className="z-btn z-btn-primary z-btn-lg whitespace-nowrap"
                  data-testid="note-finalize-button"
                >
                  작성 완료
                </button>
              </div>
            </>
          )}
        </div>
      </div>

      {confirmOpen && !finalized && (
        <div className="z-backdrop">
          <div className="z-modal max-w-[420px]">
            <h2 className="mb-2.5 text-[19px] font-extrabold">
              {isEmpty ? "메모 없이 완료할까요?" : "작성을 완료할까요?"}
            </h2>
            <p className="mb-[22px] text-sm leading-[1.6] text-ink-muted">
              {isEmpty
                ? "작성한 사후 메모가 없습니다. 메모 없이도 분석을 진행할 수 있습니다."
                : "작성을 완료하면 메모를 수정할 수 없고, 사후 분석이 시작됩니다."}
            </p>
            <div className="flex gap-2.5">
              <button
                onClick={() => setConfirmOpen(false)}
                disabled={finalizing}
                className="z-btn z-btn-outline flex-1 rounded-[13px] py-[13px]"
                data-testid="note-finalize-cancel"
              >
                {isEmpty ? "계속 작성" : "취소"}
              </button>
              <button
                onClick={confirmFinalize}
                disabled={finalizing}
                className="z-btn z-btn-primary flex-1 rounded-[13px] py-[13px]"
                data-testid="note-finalize-confirm"
              >
                {finalizing ? "완료 중…" : isEmpty ? "메모 없이 완료" : "작성 완료"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
