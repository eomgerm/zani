"use client";

import { useCallback, useEffect, useRef, useState } from "react";

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
 * 질의응답 카드의 대화 상태.
 *
 * <p>서버가 대화를 저장하지 않으므로 이력은 여기에만 있다. 새로고침하면 사라지는 것이 의도다 — 학생 질문을
 * 적재하지 않기로 한 선택의 대가이자, 보존 기간과 강사 노출 여부를 정할 일이 없어지는 이유다.
 *
 * <p>드래그한 내용이 곧 첫 질문이다. 버튼을 누르는 순간 그 텍스트가 질문으로 나가며, 무엇을 물을지 따로
 * 입력하지 않는다 — 읽다가 막힌 곳을 짚는 동작에 "그래서 뭘 물을 건가" 를 한 번 더 묻는 셈이라서다.
 * 입력칸은 후속 질문에만 쓴다.
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
  /** 드래그한 텍스트. 카드 헤더가 무엇에 대해 묻는 중인지 보여줄 때도 쓴다. */
  readonly selectedText: string;
};

export type AssistantStatus = "idle" | "asking";

export type UseReportAssistantOptions = {
  readonly sessionId: string;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly ask?: ReportQuestionAsker;
};

/**
 * 드래그한 부분을 그대로 첫 질문으로 만든다.
 *
 * <p>선택 텍스트만 덩그러니 보내지 않는 이유는 명사구 하나로는 무엇을 원하는지가 모델에게 모호하기 때문이다 —
 * "개방주소법" 만 오면 정의를 달라는 것인지 이 수업에서 어떻게 다뤘는지를 묻는 것인지 갈린다. 화면의 동작이
 * "이 부분 설명해줘" 이므로 그 뜻을 문장으로 적어 보낸다.
 */
const autoQuestionOf = (selectedText: string): string =>
  `"${selectedText}" 이 부분에 대해 이 수업에서 다룬 내용을 설명해줘.`;

/** 실패 문구를 코드로 가른다. 상태코드만으로는 "다시 눌러라" 와 "질문을 줄여라" 가 갈리지 않는다. */
const failureTextOf = (error: unknown): string => {
  if (error instanceof ReportAssistantError) {
    if (error.code === ASSISTANT_RATE_LIMITED) {
      return "질문이 너무 빨라요. 잠시 후 다시 물어봐 주세요.";
    }
    if (error.code === ASSISTANT_QUESTION_TOO_LARGE) {
      return "선택한 부분이 너무 길어요. 조금 줄여서 다시 선택해 주세요.";
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
  const { sessionId, ask: apiAsk = askReportQuestion } = options;
  const { accessToken } = useAuth();

  const [anchor, setAnchor] = useState<AssistantAnchor | null>(null);
  const [messages, setMessages] = useState<readonly AssistantMessage[]>([]);
  const [status, setStatus] = useState<AssistantStatus>("idle");
  const [failure, setFailure] = useState<string | null>(null);

  // 답을 기다리는 동안 카드를 닫거나 다른 곳을 드래그하면 앞의 응답을 버린다. 닫힌 대화나
  // 지나간 앵커에 말풍선이 늦게 붙으면, 사용자는 자기가 묻지 않은 것에 대한 답을 읽는다.
  const controllerRef = useRef<AbortController | null>(null);
  const nextId = useRef(0);

  // 이력을 상태 대신 ref 로도 들고 있는 이유: send 가 messages 에 의존하면 말풍선이 붙을 때마다
  // 새 함수가 되고, 그 함수를 useEffect 의 의존성으로 쓰는 화면에서 자동 질문이 다시 발사된다.
  const messagesRef = useRef<readonly AssistantMessage[]>([]);
  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  const run = useCallback(
    async (question: string, forAnchor: AssistantAnchor, withHistory: boolean) => {
      if (accessToken === null) return;

      // 앞의 요청이 아직 살아 있으면 끊는다. 드래그를 연달아 하면 응답 순서가 뒤집힐 수 있다.
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;

      // 이력은 이번 질문을 넣기 **전**의 대화다. 방금 쓴 질문을 이력에도 담으면 두 번 보내진다.
      const history: ReportAssistantTurn[] = withHistory
        ? messagesRef.current
            .slice(-MAX_HISTORY_TURNS)
            .map((message) => ({ role: message.role, content: message.content }))
        : [];

      setMessages((previous) => [
        ...previous,
        {
          id: `q${nextId.current++}`,
          role: "user",
          content: question,
          citations: [],
          grounded: true,
        },
      ]);
      setStatus("asking");
      setFailure(null);

      try {
        const answer = await apiAsk(
          sessionId,
          {
            question,
            selectedText: forAnchor.selectedText,
            anchorStartMs: forAnchor.anchorStartMs,
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
      }
    },
    [accessToken, apiAsk, sessionId],
  );

  /**
   * 드래그한 부분을 그대로 물어본다. 카드가 없으면 열면서 묻는다.
   *
   * <p>새 드래그는 새 대화다. 대화를 이어 붙이면 앞의 구간에 대한 질문과 답이 카드에 쌓여, 지금 무엇에 대해
   * 읽고 있는지가 흐려진다. 후속 질문은 입력칸이 맡는다.
   *
   * <p>앵커를 상태에서 읽지 않고 인자로 받는 것이 중요하다. 여는 것과 묻는 것이 같은 렌더에서 일어나므로
   * {@code setAnchor} 직후에 상태를 읽으면 이전 앵커(또는 null)가 잡힌다.
   */
  const askAbout = useCallback(
    (next: AssistantAnchor) => {
      setAnchor(next);
      setMessages([]);
      messagesRef.current = [];
      void run(autoQuestionOf(next.selectedText), next, false);
    },
    [run],
  );

  /** 입력칸의 후속 질문. 앵커는 그대로 두고 대화를 이어간다. */
  const send = useCallback(
    (question: string) => {
      const trimmed = question.trim();
      if (trimmed.length === 0 || anchor === null || status === "asking") return;
      void run(trimmed, anchor, true);
    },
    [anchor, run, status],
  );

  const close = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    setAnchor(null);
    setMessages([]);
    messagesRef.current = [];
    setStatus("idle");
    setFailure(null);
  }, []);

  return { anchor, messages, status, failure, askAbout, send, close };
}
