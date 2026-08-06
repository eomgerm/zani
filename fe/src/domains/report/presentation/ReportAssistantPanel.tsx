"use client";

import { useEffect, useRef, useState } from "react";

import { formatOffset } from "./offsetTime";
import type { AssistantAnchor, AssistantMessage, AssistantStatus } from "./useReportAssistant";
import type { ReportAnswerCitation } from "../infrastructure/reportAssistantApi";

/**
 * 드래그한 곳에 대해 묻는 채팅 드로어. 오른쪽에서 밀려 들어온다.
 *
 * <p>카드 안에 붙이지 않고 오른쪽 드로어로 띄우는 이유는 <b>읽던 자리가 밀리지 않아야</b> 하기 때문이다.
 * 카드 아래에 펼치면 답이 길어질수록 방금 드래그한 문장이 위로 밀려 올라가, 무엇을 물었는지 보면서
 * 답을 읽을 수 없다.
 *
 * <p>배경을 덮지 않는다(backdrop 없음). 답을 읽다가 다른 구간을 이어서 드래그하는 흐름이 이 기능의
 * 핵심이라, 뒤를 막으면 그 흐름이 끊긴다.
 *
 * <p>헤더에 무엇에 대해 묻는 중인지 남긴다. 후속 질문에는 새 드래그가 없어 앵커가 화면에서 사라지는데,
 * 그러면 "그건 왜 그래?" 가 어느 구간을 가리키는지 사용자도 알 수 없다.
 *
 * <p>인용은 버튼이다. 누르면 강의 녹화가 그 시각으로 이동한다 — 구간 시각 버튼과 같은 문(`onSeek`)으로
 * 들어가므로 이동 방식이 두 벌로 갈리지 않는다.
 *
 * <p>스트리밍하지 않는다. GMS 가 SSE 를 지원하는지 실측되지 않았고, 블로킹 호출에 타이핑 인디케이터를
 * 붙이는 편이 확인되지 않은 경로에 기대는 것보다 낫다.
 */

/** 밀려 들어오고 나가는 시간. 닫을 때 이 시간만큼 기다렸다 언마운트해야 나가는 동작이 보인다. */
const SLIDE_MS = 260;

export function ReportAssistantPanel({
  anchor,
  messages,
  status,
  failure,
  onSend,
  onClose,
  onSeek,
}: {
  readonly anchor: AssistantAnchor;
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
  // 그쪽이 jsdom 에 없어 테스트에서 터지기도 하지만, 더 큰 이유는 scrollIntoView 가 드로어 밖의
  // 페이지까지 함께 스크롤해 읽던 자리를 옮긴다는 것이다.
  useEffect(() => {
    const list = listRef.current;
    if (list !== null) {
      list.scrollTop = list.scrollHeight;
    }
  }, [messages.length, status, failure]);

  const dismiss = () => {
    setShown(false);
    window.setTimeout(onClose, SLIDE_MS);
  };

  const submit = () => {
    if (draft.trim().length === 0 || status === "asking") return;
    onSend(draft);
    setDraft("");
  };

  return (
    <aside
      aria-label="수업 요약 질의응답"
      style={{ transitionDuration: `${SLIDE_MS}ms` }}
      className={`fixed right-0 top-0 z-40 flex h-dvh w-full max-w-[400px] flex-col border-l border-shell-toggle bg-surface shadow-xl transition-transform ease-out ${
        shown ? "translate-x-0" : "translate-x-full"
      }`}
    >
      <div className="flex items-start justify-between gap-3 border-b border-shell-toggle px-5 py-4">
        <div>
          <div className="mb-0.5 text-[14.5px] font-bold text-ink-muted">AI에게 묻기</div>
          <div className="text-[12.5px] leading-[1.5] text-ink-fainter">
            <span className="font-bold">{anchor.selectedText}</span>
            {anchor.anchorStartMs === null ? " 에 대해 묻는 중" : " 구간에 대해 묻는 중"}
          </div>
        </div>
        <button
          type="button"
          onClick={dismiss}
          aria-label="질의응답 닫기"
          className="shrink-0 cursor-pointer border-0 bg-transparent text-[13px] text-ink-fainter hover:text-ink-muted"
        >
          닫기
        </button>
      </div>

      <div ref={listRef} className="flex-1 overflow-y-auto px-5 py-4">
        {messages.length === 0 && status === "idle" && (
          <p className="text-[13px] leading-[1.7] text-ink-fainter">
            이 구간에서 다룬 내용을 물어보세요. 답변에는 실제 발화가 근거로 붙습니다.
          </p>
        )}
        <ul className="flex list-none flex-col gap-3 p-0">
          {messages.map((message) => (
            <li key={message.id} className={message.role === "user" ? "text-right" : ""}>
              <div
                className={`inline-block max-w-[88%] whitespace-pre-line rounded-[10px] px-3 py-2 text-left text-[13.5px] leading-[1.7] ${
                  message.role === "user"
                    ? "bg-primary text-white"
                    : "border border-shell-toggle bg-faint text-ink-sub"
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

      <div className="flex gap-2 border-t border-shell-toggle px-5 py-4">
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
          placeholder="이 부분에 대해 물어보세요"
          aria-label="질문 입력"
          maxLength={500}
          className="min-w-0 flex-1 rounded-[9px] border border-shell-toggle bg-surface px-3 py-2 text-[13.5px] text-ink-sub outline-none focus:border-primary"
        />
        <button
          type="button"
          onClick={submit}
          disabled={status === "asking" || draft.trim().length === 0}
          className="z-btn z-btn-primary z-btn-md shrink-0 disabled:cursor-not-allowed disabled:opacity-50"
        >
          보내기
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
              className="max-w-full truncate rounded-full bg-faint px-2.5 py-1 text-[11.5px] text-ink-fainter"
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
            className="max-w-full cursor-pointer truncate rounded-full border border-shell-toggle bg-surface px-2.5 py-1 text-left text-[11.5px] text-primary/80 hover:text-primary"
          >
            {label}
          </button>
        );
      })}
    </div>
  );
}
