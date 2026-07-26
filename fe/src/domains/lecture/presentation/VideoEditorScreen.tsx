"use client";

import { useState } from "react";
import Link from "next/link";
import { lectures } from "./fixtures";

const TOTAL_SECONDS = 2 * 3600 + 5 * 60 + 30; // 2:05:30

function pctToTime(p: number) {
  const tot = Math.round((p / 100) * TOTAL_SECONDS);
  const hh = Math.floor(tot / 3600);
  const mm = Math.floor((tot % 3600) / 60);
  const ss = tot % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return hh > 0 ? `${hh}:${pad(mm)}:${pad(ss)}` : `${pad(mm)}:${pad(ss)}`;
}

const darkBtnCls =
  "z-btn size-[38px] shrink-0 rounded-[11px] border border-panel-line-soft bg-panel-btn text-base text-panel-text-faint";

/**
 * 영상 편집기 · 구간 컷. 타임라인에서 구간을 선택해 잘라낸다.
 * 실제 영상 처리는 붙이지 않았고 선택/삭제 구간만 로컬 상태로 관리한다.
 */
export function VideoEditorScreen({ lectureId }: { lectureId: string }) {
  const lecture = lectures.find((l) => l.id === lectureId) ?? lectures[2];
  const [start, setStart] = useState(20);
  const [end, setEnd] = useState(35);
  const [cuts, setCuts] = useState<{ s: number; e: number }[]>([]);

  const lo = Math.min(start, end);
  const hi = Math.max(start, end);
  const removed = cuts.reduce((sum, c) => sum + (c.e - c.s), 0);
  const remainPct = Math.max(0, 100 - removed);

  return (
    <div className="flex min-h-screen flex-col bg-panel text-panel-text">
      <div className="flex items-center gap-3.5 border-b border-panel-line px-6 py-4">
        <Link href={`/my-lectures/${lecture.id}/report`} className={darkBtnCls}>
          ←
        </Link>
        <div className="flex-1">
          <div className="text-[17px] font-extrabold">영상 편집기 · 구간 컷</div>
          <div className="text-[12.5px] text-panel-text-muted">{lecture.title} · 2:05:30</div>
        </div>
        <Link
          href={`/my-lectures/${lecture.id}/report`}
          className="z-btn rounded-[11px] border border-panel-line-soft bg-panel-btn px-[18px] py-[11px] text-[13.5px] text-panel-text-faint"
        >
          취소
        </Link>
        <button className="z-btn z-btn-primary rounded-[11px] px-5 py-[11px] text-[13.5px]">
          저장
        </button>
      </div>

      <div className="mx-auto flex w-full max-w-[1200px] flex-1 flex-col gap-[22px] px-6 py-[26px]">
        {/* 미리보기 */}
        <div className="relative flex aspect-[16/7] items-center justify-center overflow-hidden rounded-2xl border border-panel-line bg-panel-video">
          <div className="font-mono text-[30px] font-extrabold text-primary-bright">{lecture.title}</div>
          <span className="absolute bottom-4 left-1/2 flex size-[54px] -translate-x-1/2 cursor-pointer items-center justify-center rounded-full bg-white/[.13] text-xl text-white">
            ▶
          </span>
        </div>

        {/* 타임라인 */}
        <div className="rounded-2xl border border-panel-line bg-panel-2 px-[22px] py-5">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-2.5">
            <div className="text-[14.5px] font-extrabold">
              타임라인 · 자를 구간을 선택하세요
              <span className="ml-1.5 text-[12.5px] font-semibold text-panel-text-muted">
                선택 {pctToTime(lo)} – {pctToTime(hi)}
              </span>
            </div>
            <div className="flex items-center gap-2.5">
              <span className="text-xs text-panel-text-muted">
                남은 길이 <b className="text-primary-bright">{pctToTime(remainPct)}</b>
              </span>
              <button
                onClick={() => hi - lo > 0 && setCuts((prev) => [...prev, { s: lo, e: hi }])}
                className="z-btn z-btn-danger rounded-[9px] px-3.5 py-2 text-[12.5px]"
              >
                ✂ 선택 구간 자르기
              </button>
            </div>
          </div>

          <div className="relative h-16 overflow-hidden rounded-[10px] border border-panel-line bg-panel">
            <div className="absolute inset-0 flex items-center gap-0.5 px-1">
              <div className="h-[38px] flex-1 rounded [background:repeating-linear-gradient(90deg,#2a2f4e,#2a2f4e_3px,#232744_3px,#232744_6px)]" />
            </div>
            {/* 잘라낸 구간은 45° 해치 패턴으로, 좌우 경계만 빨간 선으로 막는다 */}
            {cuts.map((c, i) => (
              <div
                key={i}
                className="absolute inset-y-0 border-x-2 border-danger [background:repeating-linear-gradient(45deg,#e0455f55,#e0455f55_4px,#12152a_4px,#12152a_8px)]"
                style={{ left: `${c.s}%`, width: `${c.e - c.s}%` }}
              />
            ))}
            <div
              className="absolute inset-y-0 border-x-[3px] border-violet bg-[#10bfa433]"
              style={{ left: `${lo}%`, width: `${hi - lo}%` }}
            />
          </div>

          <div className="mt-3.5 flex flex-col gap-2.5">
            <div className="flex items-center gap-2.5">
              <span className="w-[60px] shrink-0 text-[11.5px] text-panel-text-muted">
                시작 {start}%
              </span>
              <input
                type="range"
                min={0}
                max={100}
                value={start}
                onChange={(e) => setStart(+e.target.value)}
                className="flex-1 cursor-pointer accent-violet"
              />
            </div>
            <div className="flex items-center gap-2.5">
              <span className="w-[60px] shrink-0 text-[11.5px] text-panel-text-muted">
                끝 {end}%
              </span>
              <input
                type="range"
                min={0}
                max={100}
                value={end}
                onChange={(e) => setEnd(+e.target.value)}
                className="flex-1 cursor-pointer accent-violet"
              />
            </div>
          </div>

          <div className="mt-2 flex justify-between font-mono text-[11px] text-panel-text-muted">
            <span>00:00</span>
            <span>30:00</span>
            <span>1:00:00</span>
            <span>1:30:00</span>
            <span>2:05:30</span>
          </div>

          {cuts.length > 0 && (
            <div className="mt-4">
              <div className="mb-2 text-xs text-panel-text-muted">잘라낸 구간</div>
              <div className="flex flex-wrap gap-2.5">
                {cuts.map((c, i) => (
                  <div
                    key={i}
                    className="flex items-center gap-2 rounded-[9px] border border-[#5a3040] bg-[#2a1e2c] px-3 py-2 text-[12.5px]"
                  >
                    <span className="font-mono font-extrabold text-danger-light">
                      {pctToTime(c.s)} – {pctToTime(c.e)}
                    </span>
                    <button
                      onClick={() => setCuts((prev) => prev.filter((_, j) => j !== i))}
                      className="cursor-pointer border-0 bg-transparent text-danger-light"
                    >
                      ✕
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
