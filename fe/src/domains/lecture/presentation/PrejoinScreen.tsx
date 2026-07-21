"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

const MIC_BARS = Array.from({ length: 14 }, (_, i) => ({
  duration: 0.6 + (i % 5) * 0.12,
  delay: i * 0.05,
}));

/**
 * SC-08 입장 전 점검. 얼굴 위치·움직임 확인 단계를 거쳐 강의실로 입장한다.
 * 실제 카메라 대신 단계 스텝을 로컬 상태로 시연한다.
 */
export function PrejoinScreen({ inviteCode }: { inviteCode: string }) {
  const router = useRouter();
  const [step, setStep] = useState<1 | 2 | 3>(1);

  return (
    <div className="flex min-h-screen items-center justify-center bg-mint p-7">
      <div className="w-full max-w-[1200px]">
        <div className="mb-5 flex items-center gap-3">
          <Link
            href="/home"
            className="z-btn size-11 rounded-[13px] border border-line-muted bg-surface text-[17px] text-ink-sub shadow-[0_2px_8px_rgba(24,74,62,.05)]"
          >
            ←
          </Link>
          <div>
            <div className="text-[22px] font-extrabold tracking-[-.4px]">입장 전 점검</div>
            <div className="mt-0.5 text-[13.5px] text-ink-faint">
              초대 코드 <b className="text-primary">{inviteCode}</b> · 카메라와 마이크를 확인해
              주세요
            </div>
          </div>
        </div>

        <div className="grid grid-cols-[1.55fr_1fr] items-stretch gap-[22px]">
          {/* 카메라 프리뷰 */}
          <div className="relative min-h-[540px] overflow-hidden rounded-[22px] bg-[#1a1d30]">
            <div className="absolute inset-0 [background:repeating-linear-gradient(135deg,#1e2138,#1e2138_16px,#232744_16px,#232744_32px)]" />

            <div className="absolute inset-0">
              {/* 상단 좌 */}
              <div className="absolute left-[18px] top-[18px] flex items-center gap-2.5">
                {step < 3 ? (
                  <>
                    <span className="rounded-[9px] bg-primary px-[11px] py-[5px] text-[12.5px] font-extrabold text-white">
                      {step} / 2
                    </span>
                    <span className="text-sm font-extrabold text-white [text-shadow:0_1px_6px_#0007]">
                      {step === 1 ? "얼굴 위치 맞추기" : "움직임 확인"}
                    </span>
                  </>
                ) : (
                  <>
                    <span className="z-pill bg-primary-mint px-[11px] py-[5px] text-[12.5px] text-primary-dark">
                      ✓ 완료
                    </span>
                    <span className="text-sm font-extrabold text-white [text-shadow:0_1px_6px_#0007]">
                      점검 완료
                    </span>
                  </>
                )}
              </div>

              {/* 상단 우 */}
              <div className="absolute right-[18px] top-[18px]">
                <span className="z-stage-chip">
                  {step === 1 ? "⧉ 인식 준비 중" : step === 2 ? "◌ 움직임 분석 중" : "✓ 확인 완료"}
                </span>
              </div>

              {/* 중앙 */}
              <div className="absolute inset-0 flex flex-col items-center justify-center px-[30px] text-center">
                {step === 3 ? (
                  <>
                    <div className="mb-5 flex size-24 items-center justify-center rounded-full bg-[#41cb96] text-[46px] text-white shadow-[0_0_0_10px_#2fb57238,0_0_40px_#2fb57266]">
                      ✓
                    </div>
                    <div className="text-[26px] font-extrabold text-white [text-shadow:0_2px_10px_#0008]">
                      점검이 완료되었어요
                    </div>
                  </>
                ) : (
                  <>
                    <FaceFrame moving={step === 2} />
                    <div className="mb-2 text-2xl font-extrabold text-white [text-shadow:0_2px_10px_#0008]">
                      {step === 1
                        ? "얼굴을 프레임 안에 맞춰 주세요"
                        : "고개를 천천히 좌우로 움직여 주세요"}
                    </div>
                    <div className="text-[14.5px] leading-[1.55] text-white/85 [text-shadow:0_1px_8px_#0009]">
                      {step === 1
                        ? "얼굴과 어깨가 프레임 안에 보이도록 위치를 조정해 주세요"
                        : "얼굴 각도와 움직임이 잘 인식되는지 확인하고 있어요"}
                    </div>
                  </>
                )}
              </div>

              <div className="absolute bottom-[18px] left-[18px] z-stage-chip font-bold">
                🎥 카메라 · 720p
              </div>
            </div>
          </div>

          {/* 우측 */}
          <div className="flex flex-col gap-4">
            <div className="z-card-lg px-[22px] py-5">
              <div className="mb-[15px] text-base font-extrabold">장치 확인</div>
              <div className="flex flex-col gap-[13px]">
                {["브라우저 · Chrome", "카메라 · 720p 로지텍", "마이크 입력 레벨"].map((t) => (
                  <div key={t} className="flex items-center gap-[11px]">
                    <span className="z-check">✓</span>
                    <span className="flex-1 text-sm font-semibold">{t}</span>
                  </div>
                ))}
                <div className="flex h-[26px] items-end gap-[3px] pl-[35px]">
                  {MIC_BARS.map((b, i) => (
                    <span
                      key={i}
                      className="w-1.5 rounded-[3px] bg-primary"
                      style={{ animation: `zLevel ${b.duration}s ease-in-out ${b.delay}s infinite` }}
                    />
                  ))}
                </div>
                {step === 3 && (
                  <div className="flex items-center gap-[11px]">
                    <span className="z-check">✓</span>
                    <span className="flex-1 text-sm font-semibold">
                      얼굴 인식 및 움직임 확인 완료
                    </span>
                  </div>
                )}
              </div>
            </div>

            <div className="z-card-lg flex flex-col gap-2 px-[22px] py-[18px]">
              <div className="text-[13px] font-bold text-ink-faint">카메라</div>
              <div className="flex items-center justify-between rounded-xl border border-line-soft bg-faint px-[15px] py-3 text-sm">
                Logitech C920 HD
              </div>
              <div className="mt-1 text-[13px] font-bold text-ink-faint">마이크</div>
              <div className="flex items-center justify-between rounded-xl border border-line-soft bg-faint px-[15px] py-3 text-sm">
                기본 - 내장 마이크
              </div>
            </div>

            <div className="flex gap-[13px] rounded-[20px] border border-line-mint bg-canvas px-5 py-[18px]">
              <span className="shrink-0 text-xl text-primary">⧉</span>
              <div>
                <div className="mb-[5px] text-[13.5px] font-extrabold">이렇게 활용돼요</div>
                <p className="text-[12.5px] leading-[1.6] text-ink-faint">
                  카메라는 수업 중 표정, 시선, 고개 움직임 등을 분석해 이해도와 참여도를 파악하는 데
                  사용돼요. 분석 결과는 <span className="font-bold text-primary">본인에게만</span>{" "}
                  제공되며 안전하게 보호됩니다.
                </p>
              </div>
            </div>

            <div className="flex-1" />
            <div className="flex gap-3">
              {step < 3 ? (
                <button
                  onClick={() => setStep((s) => (s < 3 ? ((s + 1) as 2 | 3) : s))}
                  className="z-btn z-btn-primary z-btn-block"
                >
                  다음 단계
                </button>
              ) : (
                <button
                  onClick={() => router.push(`/room/${inviteCode}`)}
                  className="z-btn z-btn-primary z-btn-block"
                >
                  수업 입장하기
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** 얼굴 정렬 가이드 프레임 (모서리 4개 + 좌우 화살표) */
function FaceFrame({ moving }: { moving: boolean }) {
  return (
    <div className="relative mb-[22px] h-[270px] w-[230px]">
      <span className="absolute left-0 top-0 size-10 rounded-tl-2xl border-l-[3px] border-t-[3px] border-white" />
      <span className="absolute right-0 top-0 size-10 rounded-tr-2xl border-r-[3px] border-t-[3px] border-white" />
      <span className="absolute bottom-0 left-0 size-10 rounded-bl-2xl border-b-[3px] border-l-[3px] border-white" />
      <span className="absolute bottom-0 right-0 size-10 rounded-br-2xl border-b-[3px] border-r-[3px] border-white" />
      {moving && (
        <>
          <span className="absolute -left-16 top-1/2 -translate-y-1/2 text-3xl text-white/70">‹</span>
          <span className="absolute -right-16 top-1/2 -translate-y-1/2 text-3xl text-white/70">›</span>
        </>
      )}
    </div>
  );
}
