"use client";

import { useEffect, useRef, useState } from "react";

import { formatOffset } from "./offsetTime";
import type { AssistantMessage, AssistantStatus } from "./useReportAssistant";
import type { ReportAnswerCitation } from "../infrastructure/reportAssistantApi";

/**
 * 드래그한 곳에 대한 답을 띄우는 작은 채팅 카드.
 *
 * <p>화면 절반을 덮는 패널이 아니라 <b>메모지만 한 카드</b>다. 이 기능은 리포트를 읽다가 막힌 곳을 짚는
 * 것이라, 답을 보려고 읽던 화면을 잃으면 안 된다. 오른쪽 아래에 띄워 뒤가 계속 보이게 한다.
 *
 * <p>배경을 덮지 않는다. 답을 읽다가 다른 구간을 이어서 드래그하는 흐름이 이 기능의 핵심이라, 뒤를 막으면
 * 그 흐름이 끊긴다.
 *
 * <p>첫 질문은 드래그가 이미 보냈다. 입력칸은 후속 질문 전용이라 안내 문구도 "추가로 질문하기" 다.
 *
 * <p>인용은 버튼이다. 누르면 강의 녹화가 그 시각으로 이동한다 — 구간 시각 버튼과 같은 문(`onSeek`)으로
 * 들어가므로 이동 방식이 두 벌로 갈리지 않는다.
 */

/** 떠오르고 사라지는 시간. 닫을 때 이만큼 기다렸다 언마운트해야 사라지는 동작이 보인다. */
const FADE_MS = 200;

export function ReportAssistantPanel({
  messages,
  status,
  failure,
  onSend,
  onClose,
  onSeek,
}: {
  readonly messages: readonly AssistantMessage[];
  readonly status: AssistantStatus;
  readonly failure: string | null;
  readonly onSend: (question: string) => void;
  readonly onClose: () => void;
  readonly onSeek?: (offsetSeconds: number) => void;
}) {
  const [draft, setDraft] = useState("");
  const [shown, setShown] = useState(false);
  const listRef = useRef<HTMLDivElement | null>(null);

  // 마운트된 뒤 한 프레임 지나서 켠다. 처음부터 열린 상태로 그리면 transition 이 걸릴 구간이 없어
  // 애니메이션 없이 튀어나온다.
  useEffect(() => {
    const frame = requestAnimationFrame(() => setShown(true));
    return () => cancelAnimationFrame(frame);
  }, []);

  // 새 말풍선이 붙으면 아래로 따라간다. 답이 길면 시작 부분만 보이고 끝이 잘린다.
  //
  // scrollIntoView 가 아니라 scrollTop 을 쓰는 것은 채팅 목록(RoomSidePanel)과 같은 방식이라서다.
  // 그쪽이 jsdom 에 없어 테스트에서 터지기도 하지만, 더 큰 이유는 scrollIntoView 가 카드 밖의
  // 페이지까지 함께 스크롤해 읽던 자리를 옮긴다는 것이다.
  useEffect(() => {
    const list = listRef.current;
    if (list !== null) {
      list.scrollTop = list.scrollHeight;
    }
  }, [messages.length, status, failure]);

  const dismiss = () => {
    setShown(false);
    window.setTimeout(onClose, FADE_MS);
  };

  const submit = () => {
    if (draft.trim().length === 0 || status === "asking") return;
    onSend(draft);
    setDraft("");
  };

  return (
    <aside
      aria-label="ZANI AI 질의응답"
      style={{ transitionDuration: `${FADE_MS}ms` }}
      className={`fixed bottom-6 right-6 z-40 flex max-h-[60vh] w-[min(380px,calc(100vw-3rem))] flex-col overflow-hidden rounded-[16px] border border-line bg-surface shadow-xl transition-all ease-out ${
        shown ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0"
      }`}
    >
      <div className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
        <div className="text-[15px] font-bold text-primary">ZANI AI</div>
        <button
          type="button"
          onClick={dismiss}
          aria-label="질의응답 닫기"
          className="shrink-0 cursor-pointer border-0 bg-transparent px-1 text-[15px] leading-none text-ink-fainter hover:text-ink-muted"
        >
          ✕
        </button>
      </div>

      <div ref={listRef} className="flex-1 overflow-y-auto px-4 py-3.5">
        <ul className="flex list-none flex-col gap-2.5 p-0">
          {messages.map((message) => (
            <li key={message.id} className={message.role === "user" ? "text-right" : ""}>
              <div
                className={`inline-block max-w-[86%] whitespace-pre-line rounded-[14px] px-3.5 py-2.5 text-left text-[13.5px] leading-[1.65] ${
                  message.role === "user" ? "bg-primary text-white" : "bg-faint text-ink-sub"
                }`}
              >
                {message.content}
              </div>
              {message.role === "assistant" && message.citations.length > 0 && (
                <CitationRow citations={message.citations} onSeek={onSeek} />
              )}
            </li>
          ))}
          {status === "asking" && (
            <li className="text-[13px] text-ink-fainter">답변을 만들고 있어요…</li>
          )}
          {failure !== null && <li className="text-[13px] text-danger">{failure}</li>}
        </ul>
      </div>

      <div className="flex gap-2 border-t border-line px-4 py-3">
        <input
          type="text"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            // isComposing 을 보지 않으면 한글 조합 중 Enter 가 확정과 전송을 겹쳐 처리해 두 번 보내진다.
            if (event.key === "Enter" && !event.nativeEvent.isComposing) {
              submit();
            }
          }}
          placeholder="추가로 질문하기..."
          aria-label="추가 질문 입력"
          maxLength={500}
          className="min-w-0 flex-1 rounded-[10px] border border-line bg-surface px-3 py-2 text-[13px] text-ink-sub outline-none focus:border-primary"
        />
        <button
          type="button"
          onClick={submit}
          disabled={status === "asking" || draft.trim().length === 0}
          className="z-btn z-btn-primary shrink-0 rounded-[10px] px-3.5 py-2 text-[13px] disabled:cursor-not-allowed disabled:opacity-50"
        >
          전송
        </button>
      </div>
    </aside>
  );
}

/** 근거가 된 발화. 시각은 ms 로 오지만 이동 배선의 계약은 초라 여기서 낮춘다. */
function CitationRow({
  citations,
  onSeek,
}: {
  readonly citations: readonly ReportAnswerCitation[];
  readonly onSeek?: (offsetSeconds: number) => void;
}) {
  return (
    <div className="mt-1.5 flex flex-col items-start gap-1">
      {citations.map((citation) => {
        const seconds = Math.floor(citation.offsetMs / 1000);
        const label = `${formatOffset(seconds)} ${citation.quote}`;
        // 배선이 없으면 누를 곳 없는 버튼을 내지 않는다 — 구간 시각·전사 행과 같은 약속이다.
        if (onSeek === undefined) {
          return (
            <span
              key={citation.offsetMs}
              className="max-w-full truncate rounded-full bg-faint px-2.5 py-1 text-[11px] text-ink-fainter"
            >
              {label}
            </span>
          );
        }
        return (
          <button
            key={citation.offsetMs}
            type="button"
            onClick={() => onSeek(seconds)}
            aria-label={`${formatOffset(seconds)} 구간 재생`}
            className="max-w-full cursor-pointer truncate rounded-full border border-line bg-surface px-2.5 py-1 text-left text-[11px] text-primary/80 hover:text-primary"
          >
            {label}
          </button>
        );
      })}
    </div>
  );
}
