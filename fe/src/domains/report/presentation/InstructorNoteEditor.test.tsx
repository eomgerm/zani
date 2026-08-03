import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi, type Mock } from "vitest";

import {
  InstructorNoteRequestError,
  NOTE_ALREADY_FINALIZED,
  NOTE_CONTENT_TOO_LONG,
  SESSION_STILL_LIVE,
  type NoteDraftSaver,
  type NoteFinalizer,
} from "../infrastructure/instructorNoteApi";
import { InstructorNoteEditor } from "./InstructorNoteEditor";

const push = vi.fn();
const auth = vi.hoisted(() => ({
  accessToken: "test-access-token" as string | null,
  isInitializing: false,
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
vi.mock("@/domains/auth", () => ({
  useAuth: () => ({ accessToken: auth.accessToken, isInitializing: auth.isInitializing }),
}));

const draftResult = { noteId: "1", status: "DRAFT" as const, updatedAt: "2026-07-30T09:12:00Z" };
const finalizedResult = {
  noteId: "1",
  status: "FINALIZED" as const,
  updatedAt: "2026-07-30T09:20:00Z",
};

function renderEditor(overrides: {
  saveDraftRequest?: Mock<NoteDraftSaver>;
  finalizeRequest?: Mock<NoteFinalizer>;
} = {}) {
  const saveDraftRequest =
    overrides.saveDraftRequest ?? vi.fn<NoteDraftSaver>().mockResolvedValue(draftResult);
  const finalizeRequest =
    overrides.finalizeRequest ?? vi.fn<NoteFinalizer>().mockResolvedValue(finalizedResult);
  render(
    <InstructorNoteEditor
      sessionId="123"
      autoSaveDelayMs={0}
      saveDraftRequest={saveDraftRequest}
      finalizeRequest={finalizeRequest}
    />,
  );
  return { saveDraftRequest, finalizeRequest };
}

beforeEach(() => {
  push.mockReset();
  auth.accessToken = "test-access-token";
  auth.isInitializing = false;
});

describe("InstructorNoteEditor", () => {
  it("auto-saves the draft after typing stops and says the inactivity timer was reset", async () => {
    const { saveDraftRequest } = renderEditor();

    fireEvent.change(screen.getByTestId("note-textarea"), {
      target: { value: "재귀 종료 조건 정리" },
    });

    await waitFor(() =>
      expect(saveDraftRequest).toHaveBeenCalledWith(
        "123",
        "재귀 종료 조건 정리",
        "test-access-token",
        expect.any(AbortSignal),
      ),
    );
    await waitFor(() =>
      expect(screen.getByTestId("note-save-status").textContent).toContain(
        "30분 비활성 타이머가 초기화되었습니다",
      ),
    );
  });

  it("walks the whole flow: write, auto-save, finalize, then leaves for my lectures", async () => {
    const { saveDraftRequest, finalizeRequest } = renderEditor();

    fireEvent.change(screen.getByTestId("note-textarea"), {
      target: { value: "Context 리렌더링에서 학생들이 헷갈려 했다." },
    });
    await waitFor(() => expect(saveDraftRequest).toHaveBeenCalled());

    fireEvent.click(screen.getByTestId("note-finalize-button"));
    expect(screen.getByText("작성을 완료할까요?")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("note-finalize-confirm"));

    await waitFor(() =>
      expect(finalizeRequest).toHaveBeenCalledWith("123", "test-access-token"),
    );
    // 완료하면 확정 화면을 거치지 않고 곧바로 내 강의실로 이동한다.
    await waitFor(() => expect(push).toHaveBeenCalledWith("/my-lectures"));
    expect(screen.queryByTestId("note-finalized-content")).not.toBeInTheDocument();
  });

  it("flushes an unsaved draft before finalizing so the server sees the latest content", async () => {
    const { saveDraftRequest, finalizeRequest } = renderEditor();

    fireEvent.change(screen.getByTestId("note-textarea"), {
      target: { value: "저장 전 마지막 입력" },
    });
    // 자동 저장 타이머가 돌기 전에 바로 완료를 누른다.
    fireEvent.click(screen.getByTestId("note-finalize-button"));
    fireEvent.click(screen.getByTestId("note-finalize-confirm"));

    await waitFor(() => expect(finalizeRequest).toHaveBeenCalled());
    expect(saveDraftRequest).toHaveBeenCalledWith(
      "123",
      "저장 전 마지막 입력",
      "test-access-token",
    );
    expect(saveDraftRequest.mock.invocationCallOrder[0]).toBeLessThan(
      finalizeRequest.mock.invocationCallOrder[0],
    );
  });

  it("finalizes without a memo through the empty-note path", async () => {
    const { saveDraftRequest, finalizeRequest } = renderEditor();

    fireEvent.click(screen.getByTestId("note-finalize-button"));
    expect(screen.getByText("메모 없이 완료할까요?")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("note-finalize-confirm"));

    await waitFor(() => expect(finalizeRequest).toHaveBeenCalled());
    expect(saveDraftRequest).not.toHaveBeenCalled();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/my-lectures"));
  });

  it("keeps editing when the confirm dialog is cancelled", () => {
    const { finalizeRequest } = renderEditor();

    fireEvent.click(screen.getByTestId("note-finalize-button"));
    fireEvent.click(screen.getByTestId("note-finalize-cancel"));

    expect(finalizeRequest).not.toHaveBeenCalled();
    expect(screen.getByTestId("note-textarea")).toBeInTheDocument();
    expect(screen.queryByTestId("note-finalize-confirm")).not.toBeInTheDocument();
  });

  it("shows the 400 over-length rejection from the server", async () => {
    const saveDraftRequest = vi
      .fn<NoteDraftSaver>()
      .mockRejectedValue(
        new InstructorNoteRequestError("too long", 400, NOTE_CONTENT_TOO_LONG),
      );
    renderEditor({ saveDraftRequest });

    fireEvent.change(screen.getByTestId("note-textarea"), {
      target: { value: "아주 긴 메모" },
    });

    await waitFor(() =>
      expect(screen.getByTestId("note-error").textContent).toContain(
        "메모는 5,000자까지 저장할 수 있습니다",
      ),
    );
    expect(screen.getByTestId("note-textarea")).toBeInTheDocument();
  });

  it("switches to the read-only view when a save hits an already-finalized note", async () => {
    const saveDraftRequest = vi
      .fn<NoteDraftSaver>()
      .mockResolvedValueOnce(draftResult)
      .mockRejectedValueOnce(
        new InstructorNoteRequestError("finalized", 409, NOTE_ALREADY_FINALIZED),
      );
    renderEditor({ saveDraftRequest });

    fireEvent.change(screen.getByTestId("note-textarea"), {
      target: { value: "첫 저장 내용" },
    });
    await waitFor(() => expect(saveDraftRequest).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByTestId("note-textarea"), {
      target: { value: "첫 저장 내용 뒤에 덧붙인 입력" },
    });

    await waitFor(() =>
      expect(screen.getByTestId("note-auto-finalized-notice")).toBeInTheDocument(),
    );
    // 서버에는 마지막으로 저장된 내용까지만 반영됐다.
    expect(screen.getByTestId("note-finalized-content").textContent).toBe("첫 저장 내용");
    expect(screen.queryByTestId("note-textarea")).not.toBeInTheDocument();
  });

  it("explains a 409 from a session that is still in progress", async () => {
    const saveDraftRequest = vi
      .fn<NoteDraftSaver>()
      .mockRejectedValue(new InstructorNoteRequestError("live", 409, SESSION_STILL_LIVE));
    renderEditor({ saveDraftRequest });

    fireEvent.change(screen.getByTestId("note-textarea"), {
      target: { value: "아직 수업 중" },
    });

    await waitFor(() =>
      expect(screen.getByTestId("note-error").textContent).toContain("아직 진행 중인 수업입니다"),
    );
  });

  it("explains a still-live 409 on finalize and stays editable", async () => {
    const finalizeRequest = vi
      .fn<NoteFinalizer>()
      .mockRejectedValue(new InstructorNoteRequestError("live", 409, SESSION_STILL_LIVE));
    renderEditor({ finalizeRequest });

    fireEvent.click(screen.getByTestId("note-finalize-button"));
    fireEvent.click(screen.getByTestId("note-finalize-confirm"));

    await waitFor(() =>
      expect(screen.getByTestId("note-error").textContent).toContain("아직 진행 중인 수업입니다"),
    );
    expect(screen.getByTestId("note-textarea")).toBeInTheDocument();
    expect(screen.queryByTestId("note-finalized-notice")).not.toBeInTheDocument();
  });

  it("navigates back to my lectures from the auto-finalized read-only view", async () => {
    // 사용자가 완료를 누르지 않은 자동 확정 뒤에만 읽기 전용 화면이 뜬다. 그 화면의 버튼으로 이동한다.
    const saveDraftRequest = vi
      .fn<NoteDraftSaver>()
      .mockResolvedValueOnce(draftResult)
      .mockRejectedValueOnce(
        new InstructorNoteRequestError("finalized", 409, NOTE_ALREADY_FINALIZED),
      );
    renderEditor({ saveDraftRequest });

    fireEvent.change(screen.getByTestId("note-textarea"), { target: { value: "첫 저장 내용" } });
    await waitFor(() => expect(saveDraftRequest).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByTestId("note-textarea"), {
      target: { value: "첫 저장 내용 뒤에 덧붙인 입력" },
    });
    await waitFor(() => expect(screen.getByTestId("note-go-lectures")).toBeInTheDocument());

    fireEvent.click(screen.getByTestId("note-go-lectures"));

    expect(push).toHaveBeenCalledWith("/my-lectures");
  });

  it("asks the instructor to sign in again when there is no access token", () => {
    auth.accessToken = null;
    renderEditor();

    expect(screen.getByTestId("note-signed-out")).toBeInTheDocument();
    expect(screen.queryByTestId("note-textarea")).not.toBeInTheDocument();
  });
});
