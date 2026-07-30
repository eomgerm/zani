import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ChatMessageView } from "@/domains/interaction";
import type { Participant } from "../../fixtures";
import { RoomSidePanel } from "./RoomSidePanel";

const message = (id: string, content: string): ChatMessageView => ({
  id,
  clientEventId: null,
  senderIdentity: "p-22",
  authorName: "김민수",
  isInstructor: false,
  content,
  status: "sent",
  failureReason: null,
  mine: false,
});

const participants: Participant[] = [
  { id: "p-11", name: "박서준", color: "#10b981", host: true, cam: true, mic: true, hand: false },
];

/** jsdom 은 레이아웃을 계산하지 않아 스크롤 크기가 모두 0이다. 넘치는 목록을 흉내내려면 직접 심어야 한다. */
function makeOverflowing(list: HTMLElement, scrollHeight: number, clientHeight: number) {
  Object.defineProperty(list, "scrollHeight", { value: scrollHeight, configurable: true });
  Object.defineProperty(list, "clientHeight", { value: clientHeight, configurable: true });
}

type PanelOverrides = {
  messages?: readonly ChatMessageView[];
  canSendChat?: boolean;
  onSendChat?: (content: string) => void;
  onRetryChat?: (clientEventId: string) => void;
};

const panelWith = ({
  messages = [],
  canSendChat = true,
  onSendChat = vi.fn(),
  onRetryChat = vi.fn(),
}: PanelOverrides) => (
  <RoomSidePanel
    panel="chat"
    participants={participants}
    messages={messages}
    meId="p-11"
    isInstructor={false}
    canSendChat={canSendChat}
    onSendChat={onSendChat}
    onRetryChat={onRetryChat}
  />
);

afterEach(cleanup);

describe("RoomSidePanel 채팅 자동 스크롤", () => {
  /** 없으면 목록이 패널을 넘긴 뒤부터 새 메시지가 화면 밖에 쌓인다. */
  it("새 메시지가 오면 맨 아래로 붙인다", () => {
    const view = render(panelWith({ messages: [message("1", "첫 메시지")] }));
    const list = screen.getByTestId("chat-message-list");
    makeOverflowing(list, 500, 100);

    view.rerender(panelWith({ messages: [message("1", "첫 메시지"), message("2", "새 메시지")] }));

    expect(list.scrollTop).toBe(500);
  });

  /** 위로 올려 이력을 읽는 중에 끌려 내려가면 읽을 수가 없다. */
  it("이력을 보려고 위로 올린 상태에서는 끌어내리지 않는다", () => {
    const view = render(panelWith({ messages: [message("1", "첫 메시지")] }));
    const list = screen.getByTestId("chat-message-list");
    makeOverflowing(list, 500, 100);

    list.scrollTop = 0;
    fireEvent.scroll(list);
    view.rerender(panelWith({ messages: [message("1", "첫 메시지"), message("2", "새 메시지")] }));

    expect(list.scrollTop).toBe(0);
  });

  it("다시 맨 아래로 내려오면 따라가기를 재개한다", () => {
    const view = render(panelWith({ messages: [message("1", "첫 메시지")] }));
    const list = screen.getByTestId("chat-message-list");
    makeOverflowing(list, 500, 100);

    list.scrollTop = 0;
    fireEvent.scroll(list);
    list.scrollTop = 400; // 500 - 100 = 맨 아래
    fireEvent.scroll(list);
    view.rerender(panelWith({ messages: [message("1", "첫 메시지"), message("2", "새 메시지")] }));

    expect(list.scrollTop).toBe(500);
  });
});

describe("RoomSidePanel 채팅 입력", () => {
  it("빈 메시지는 보내지 않는다", () => {
    const onSendChat = vi.fn();
    render(panelWith({ messages: [], onSendChat: onSendChat, onRetryChat: vi.fn() }));

    fireEvent.click(screen.getByRole("button", { name: "메시지 보내기" }));

    expect(onSendChat).not.toHaveBeenCalled();
  });

  it("보낸 뒤 입력을 비운다", () => {
    const onSendChat = vi.fn();
    render(panelWith({ messages: [], onSendChat: onSendChat, onRetryChat: vi.fn() }));

    const input = screen.getByPlaceholderText("전체에게 메시지 보내기");
    fireEvent.change(input, { target: { value: "질문 있습니다" } });
    fireEvent.click(screen.getByRole("button", { name: "메시지 보내기" }));

    expect(onSendChat).toHaveBeenCalledWith("질문 있습니다");
    expect(input).toHaveValue("");
  });

  /** 재연결이 깜빡일 때 작성 중인 문장을 이어 쓸 수 있어야 한다. 보내기만 막는다. */
  it("채널이 끊겨도 입력은 열어 두고 보내기만 막는다", () => {
    render(panelWith({ messages: [], canSendChat: false, onSendChat: vi.fn(), onRetryChat: vi.fn() }));

    expect(screen.getByPlaceholderText("전체에게 메시지 보내기")).toBeEnabled();
    expect(screen.getByRole("button", { name: "메시지 보내기" })).toBeDisabled();
  });

  it("실패한 전송은 같은 clientEventId 로 다시 시도한다", () => {
    const onRetryChat = vi.fn();
    const failed: ChatMessageView = {
      ...message("c-1", "질문 있습니다"),
      clientEventId: "c-1",
      senderIdentity: null,
      status: "failed",
      failureReason: "SEND_TIMEOUT",
      mine: true,
    };
    render(panelWith({ messages: [failed], onSendChat: vi.fn(), onRetryChat: onRetryChat }));

    fireEvent.click(screen.getByRole("button", { name: "전송 실패 · 다시 시도" }));

    expect(onRetryChat).toHaveBeenCalledWith("c-1");
  });
});
