"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { TERMS, type TermDef } from "./fixtures";
import { useAuth } from "./AuthProvider";

type TermKey = TermDef["key"];

/** 체크박스 사각형. 체크 여부에 따라 채움/테두리가 바뀐다. */
function CheckBox({ on }: { on: boolean }) {
  return (
    <span
      className={`flex size-6 shrink-0 items-center justify-center rounded-lg border-[1.5px] text-sm font-black text-white ${
        on ? "border-primary bg-primary" : "border-[#d3d7ea] bg-surface"
      }`}
    >
      {on ? "✓" : ""}
    </span>
  );
}

/**
 * SC-02 약관 동의. 필수 약관 동의 + 상세 보기 모달.
 * 체크 상태는 시연용 로컬 상태이며, 전체 동의 시 홈으로 이동한다.
 */
export function TermsScreen() {
  const router = useRouter();
  const { logout } = useAuth();
  const [checks, setChecks] = useState<Record<TermKey, boolean>>({ a: false, b: false, c: false });
  const [detail, setDetail] = useState<TermDef | null>(null);

  const allOn = checks.a && checks.b && checks.c;
  const toggle = (k: TermKey) => setChecks((p) => ({ ...p, [k]: !p[k] }));
  const toggleAll = () =>
    setChecks(allOn ? { a: false, b: false, c: false } : { a: true, b: true, c: true });

  /**
   * 약관에 동의하지 않고 나가면 방금 로그인으로 만들어진 세션이 살아있으면 안 된다 —
   * 그대로 두면 필수 약관 동의를 건너뛴 채로 로그인 상태가 남아 인증 가드를 우회할 수 있다.
   */
  const backToLogin = () => {
    logout();
    router.push("/login");
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#f1f5f4] p-6">
      <div className="w-full max-w-[520px] rounded-3xl border border-line bg-surface px-8 py-[34px] shadow-soft">
        <h1 className="mb-5 text-[22px] font-extrabold">ZANI 필수 약관 및 개인정보 처리 동의</h1>

        <label
          onClick={toggleAll}
          className="mb-3.5 flex cursor-pointer items-center gap-3 rounded-[14px] border-[1.5px] border-line-muted bg-surface px-4 py-[15px]"
        >
          <CheckBox on={allOn} />
          <span className="text-[15px] font-extrabold">모든 필수 약관에 동의합니다</span>
        </label>

        <div className="flex flex-col gap-2.5">
          {TERMS.map((t) => (
            <label
              key={t.key}
              onClick={() => toggle(t.key)}
              className="flex cursor-pointer items-center gap-3 rounded-xl border border-line-mint px-4 py-[13px]"
            >
              <CheckBox on={checks[t.key]} />
              <span className="flex-1">
                <span className="mr-1.5 text-[13px] font-bold text-pink">필수</span>
                {t.label}
              </span>
              <span
                onClick={(e) => {
                  e.stopPropagation();
                  setDetail(t);
                }}
                className="cursor-pointer text-[13px] font-bold text-primary underline"
              >
                보기
              </span>
            </label>
          ))}
        </div>

        <div className="mt-[26px] flex gap-3">
          <button
            onClick={backToLogin}
            className="z-btn z-btn-outline flex-1 rounded-[14px] py-3.5 font-bold"
          >
            돌아가기
          </button>
          <button
            onClick={() => allOn && router.push("/home")}
            disabled={!allOn}
            className={`z-btn flex-[1.4] rounded-[14px] py-3.5 text-[15px] text-white ${
              allOn ? "z-btn-primary" : "cursor-not-allowed bg-disabled"
            }`}
          >
            시작하기
          </button>
        </div>
      </div>

      {detail && (
        <div onClick={() => setDetail(null)} className="z-backdrop z-[120] bg-[rgba(28,32,58,.5)]">
          <div
            onClick={(e) => e.stopPropagation()}
            className="flex max-h-[82vh] w-full max-w-[640px] animate-[zPop_.18s] flex-col rounded-[20px] bg-surface shadow-[0_24px_60px_rgba(28,32,58,.4)]"
          >
            <div className="flex items-center justify-between gap-3 border-b border-line-light px-[26px] py-[22px]">
              <div className="text-[17px] font-extrabold">{detail.label}</div>
              <button
                onClick={() => setDetail(null)}
                className="size-[34px] shrink-0 cursor-pointer rounded-[10px] border border-line-muted bg-surface text-ink-faint"
              >
                ✕
              </button>
            </div>
            <div className="flex flex-col overflow-y-auto px-[26px] py-[22px] text-[13.5px] text-ink-label">
              <TermsBody body={detail.body} />
            </div>
            <div className="border-t border-line-light px-[26px] py-4">
              <button
                onClick={() => setDetail(null)}
                className="z-btn z-btn-primary w-full rounded-[13px] py-[13px] text-[14.5px]"
              >
                확인
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 약관 본문을 줄 단위로 훑어 조항 제목·동의 문장·일반 문단을 구분해 렌더한다.
 * 본문 텍스트 자체는 손대지 않고 표현만 나눈다.
 */
function TermsBody({ body }: { body: string }) {
  return (
    <>
      {body.split("\n").map((line, i) => {
        const text = line.trim();

        if (!text) return <div key={i} className="h-1.5" />;

        if (/^제\d+조/.test(text)) {
          return (
            <div key={i} className="mb-[5px] mt-4 text-[14.5px] font-extrabold text-ink">
              {text}
            </div>
          );
        }

        // "본인은 ~ 동의합니다" 형태의 마무리 문장은 강조해 둔다.
        if (text.startsWith("본인은")) {
          return (
            <div key={i} className="mt-4 text-[13.5px] font-bold leading-[1.7] text-ink">
              {line}
            </div>
          );
        }

        return (
          <div key={i} className="mb-0.5 text-[13.5px] leading-[1.8] text-ink-label">
            {line}
          </div>
        );
      })}
    </>
  );
}
