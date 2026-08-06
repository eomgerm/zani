"use client";

import { useCallback, useRef, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  ASSISTANT_QUESTION_TOO_LARGE,
  ASSISTANT_RATE_LIMITED,
  ASSISTANT_REPORT_NOT_READY,
  askReportQuestion,
  ReportAssistantError,
  type ReportAnswerCitation,
  type ReportQuestionAsker,
  type ReportAssistantTurn,
} from "../infrastructure/reportAssistantApi";

/**
 * 질의응답 패널의 대화 상태.
 *
 * <p>서버가 대화를 저장하지 않으므로 이력은 여기에만 있다. 새로고침하면 사라지는 것이 의도다 — 학생 질문을
 * 적재하지 않기로 한 선택의 대가이자, 보존 기간과 강사 노출 여부를 정할 일이 없어지는 이유다.
 *
 * <p>앵커는 대화에 고정된다(sticky). 후속 질문마다 다시 드래그하게 하면 "그럼 그건 왜?" 같은 자연스러운
 * 되물음이 불가능하다. 다시 드래그하면 앵커만 갈리고 대화는 이어진다.
 */

/** 서버 계약과 같은 상한. 넘겨 보내면 400 이므로 여기서 자른다. */
const MAX_HISTORY_TURNS = 6;

export type AssistantMessage = {
  readonly id: string;
  readonly role: "user" | "assistant";
  readonly content: string;
  readonly citations: readonly ReportAnswerCitation[];
  /** false 면 "이 수업에서 다루지 않았어요" 로 답한 것이다. 사용자 메시지에는 뜻이 없다. */
  readonly grounded: boolean;
};

export type AssistantAnchor = {
  /** 드래그한 구간의 시작(ms). 전체 요약 문단을 짚었으면 null. */
  readonly anchorStartMs: number | null;
  /** 패널 헤더에 "무엇에 대해 묻는 중" 인지 보여줄 텍스트. */
  readonly selectedText: string;
};

export type AssistantStatus = "idle" | "asking";

export type UseReportAssistantOptions = {
  readonly sessionId: string;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly ask?: ReportQuestionAsker;
};

/** 실패 문구를 코드로 가른다. 상태코드만으로는 "다시 눌러라" 와 "질문을 줄여라" 가 갈리지 않는다. */
const failureTextOf = (error: unknown): string => {
  if (error instanceof ReportAssistantError) {
    if (error.code === ASSISTANT_RATE_LIMITED) {
      return "질문이 너무 빨라요. 잠시 후 다시 물어봐 주세요.";
    }
    if (error.code === ASSISTANT_QUESTION_TOO_LARGE) {
      return "질문이 너무 길어요. 조금 줄여서 다시 물어봐 주세요.";
    }
    if (error.code === ASSISTANT_REPORT_NOT_READY) {
      return "아직 분석이 끝나지 않아 답할 수 없어요.";
    }
    if (error.status === 403) {
      return "이 수업의 내용을 볼 수 없어요.";
    }
  }
  return "답변을 가져오지 못했어요. 다시 시도해 주세요.";
};

export function useReportAssistant(options: UseReportAssistantOptions) {
  const { sessionId, ask = askReportQuestion } = options;
  const { accessToken } = useAuth();

  const [anchor, setAnchor] = useState<AssistantAnchor | null>(null);
  const [messages, setMessages] = useState<readonly AssistantMessage[]>([]);
  const [status, setStatus] = useState<AssistantStatus>("idle");
  const [failure, setFailure] = useState<string | null>(null);

  // 답을 기다리는 동안 패널을 닫으면 응답을 버린다. 닫힌 대화에 말풍선이 늦게 붙으면 안 된다.
  const controllerRef = useRef<AbortController | null>(null);
  const nextId = useRef(0);

  /** 드래그로 앵커를 잡는다. 대화가 이미 있으면 앵커만 갈고 이어간다. */
  const openWith = useCallback((next: AssistantAnchor) => {
    setAnchor(next);
    setFailure(null);
  }, []);

  const close = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    setAnchor(null);
    setMessages([]);
    setStatus("idle");
    setFailure(null);
  }, []);

  const send = useCallback(
    async (question: string) => {
      const trimmed = question.trim();
      if (trimmed.length === 0 || accessToken === null || status === "asking") {
        return;
      }

      // 이력은 이번 질문을 넣기 **전**의 대화다. 방금 쓴 질문을 이력에도 담으면 두 번 보내진다.
      const history: ReportAssistantTurn[] = messages
        .slice(-MAX_HISTORY_TURNS)
        .map((message) => ({ role: message.role, content: message.content }));

      const askedId = `q${nextId.current++}`;
      setMessages((previous) => [
        ...previous,
        { id: askedId, role: "user", content: trimmed, citations: [], grounded: true },
      ]);
      setStatus("asking");
      setFailure(null);

      const controller = new AbortController();
      controllerRef.current = controller;

      try {
        const answer = await ask(
          sessionId,
          {
            question: trimmed,
            selectedText: anchor?.selectedText,
            anchorStartMs: anchor?.anchorStartMs ?? null,
            history,
          },
          accessToken,
          controller.signal,
        );
        if (controller.signal.aborted) return;
        setMessages((previous) => [
          ...previous,
          {
            id: `a${nextId.current++}`,
            role: "assistant",
            content: answer.answer,
            citations: answer.citations,
            grounded: answer.grounded,
          },
        ]);
      } catch (error) {
        if (controller.signal.aborted) return;
        // 보낸 질문은 말풍선으로 남긴다. 지우면 무엇이 실패했는지 알 수 없다.
        setFailure(failureTextOf(error));
      } finally {
        if (!controller.signal.aborted) {
          setStatus("idle");
        }
        controllerRef.current = null;
      }
    },
    [accessToken, anchor, ask, messages, sessionId, status],
  );

  return { anchor, messages, status, failure, openWith, close, send };
}
