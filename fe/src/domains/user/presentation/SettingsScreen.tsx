"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Avatar, CalendarIcon, Card, FileIcon } from "@/shared/ui";
import { getCurrentMember, useAuth } from "@/domains/auth";
import { updateReportEmail } from "../infrastructure/updateReportEmailApi";

function Toggle({ on, onClick }: { on: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`relative h-7 w-12 shrink-0 cursor-pointer rounded-full border-0 transition-colors ${
        on ? "bg-primary" : "bg-[#d7dbee]"
      }`}
    >
      <span
        className={`absolute top-[3px] size-[22px] rounded-full bg-white shadow-[0_1px_3px_rgba(0,0,0,.2)] transition-[left] ${
          on ? "left-[23px]" : "left-[3px]"
        }`}
      />
    </button>
  );
}

function NotifRow({
  icon,
  title,
  desc,
  on,
  onToggle,
}: {
  icon: React.ReactNode;
  title: string;
  desc: string;
  on: boolean;
  onToggle: () => void;
}) {
  return (
    <div className="flex items-center gap-3.5 border-t border-line-light py-4">
      <span className="flex size-[38px] items-center justify-center rounded-[11px] bg-primary-soft">
        {icon}
      </span>
      <div className="min-w-0 flex-1">
        <div className="text-[14.5px] font-extrabold">{title}</div>
        <div className="mt-[3px] text-[12.5px] leading-[1.5] text-ink-fainter">{desc}</div>
      </div>
      <Toggle on={on} onClick={onToggle} />
    </div>
  );
}

/**
 * SC-07 계정 설정. 프로필 · 알림 설정 · 계정 관리(회원 탈퇴).
 * "강의 리포트 알림" 토글은 서버 설정(GET·PATCH /api/v1/members/me)에 연결돼 실제 이메일 수신 여부를 바꾼다.
 * 이름 편집·수업 일정 알림·탈퇴는 아직 시연용 로컬 상태로만 동작한다.
 */
export function SettingsScreen() {
  const router = useRouter();
  const { member, accessToken } = useAuth();
  const [name, setName] = useState<string>(member?.displayName ?? "");
  const [notifSchedule, setNotifSchedule] = useState(true);
  const [notifReport, setNotifReport] = useState(true);
  const [reportPending, setReportPending] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const nameChanged = name.trim() !== (member?.displayName ?? "").trim() && name.trim().length > 0;

  // 저장된 리포트 알림 수신 설정을 서버에서 읽어 토글 초기값을 맞춘다. 토큰이 없으면(비로그인) 건드리지 않는다.
  useEffect(() => {
    if (accessToken === null) return;
    const controller = new AbortController();
    getCurrentMember(accessToken, controller.signal)
      .then((current) => setNotifReport(current.reportEmailEnabled))
      .catch(() => {
        // 조회 실패는 조용히 무시한다 — 기본값(수신)을 유지하고 사용자가 다시 토글하면 그때 반영된다.
      });
    return () => controller.abort();
  }, [accessToken]);

  // "강의 리포트 알림"을 켜고 끈다. 낙관적으로 먼저 바꾸고, 서버 반영에 실패하면 이전 값으로 되돌린다.
  const toggleReport = () => {
    if (accessToken === null || reportPending) return;
    const next = !notifReport;
    setNotifReport(next);
    setReportPending(true);
    updateReportEmail(accessToken, next)
      .then((result) => setNotifReport(result.reportEmailEnabled))
      .catch(() => setNotifReport(!next))
      .finally(() => setReportPending(false));
  };

  return (
    <>
      <h1 className="mb-1 text-[26px] font-extrabold tracking-[-.5px]">계정 설정</h1>
      <p className="mb-[26px] text-ink-muted">프로필과 알림을 관리해요.</p>

      <div className="flex max-w-[1180px] flex-col gap-5">
        {/* 프로필 정보 */}
        <Card className="px-[30px] py-7">
          <div className="mb-5 text-[17px] font-extrabold">프로필 정보</div>
          <div className="flex flex-wrap items-start gap-[34px]">
            <Avatar initial={name.charAt(0)} size={84} fontSize={26} />
            <div className="flex min-w-[260px] flex-1 flex-wrap gap-[22px]">
              <div className="min-w-[200px] flex-1">
                <label className="mb-[7px] block text-[13px] font-bold text-ink-faint">이름</label>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="z-input rounded-[11px] px-3.5 py-3"
                />
              </div>
              <div className="min-w-[200px] flex-1">
                <label className="mb-[7px] block text-[13px] font-bold text-ink-faint">
                  이메일 (변경 불가)
                </label>
                <div className="z-field-readonly rounded-[11px] px-3.5 py-3 text-sm">
                  {member?.email ?? ""}
                </div>
              </div>
            </div>
          </div>
          <div className="mt-5 flex justify-end">
            {/* 이름을 바꾸지 않았으면 저장할 것이 없어 비활성으로 둔다 */}
            <button
              type="button"
              disabled={!nameChanged}
              className={`z-btn z-btn-md ${
                nameChanged ? "z-btn-primary" : "cursor-not-allowed bg-disabled text-white"
              }`}
            >
              저장하기
            </button>
          </div>
        </Card>

        {/* 알림 설정 */}
        <Card className="px-[30px] py-7">
          <div className="mb-1.5 text-[17px] font-extrabold">알림 설정</div>
          <p className="mb-[18px] text-[13.5px] leading-[1.55] text-ink-faint">
            이메일로 알림을 받아보실 수 있습니다.
          </p>
          <NotifRow
            icon={<CalendarIcon size={18} className="text-primary" />}
            title="수업 일정 알림"
            desc="예약된 수업 일정이 시작되기 전에 알림을 받습니다."
            on={notifSchedule}
            onToggle={() => setNotifSchedule((v) => !v)}
          />
          <NotifRow
            icon={<FileIcon size={18} className="text-[#15bd7d]" />}
            title="강의 리포트 알림"
            desc="수업 리포트가 생성되면 이메일로 알림을 받습니다."
            on={notifReport}
            onToggle={toggleReport}
          />
        </Card>

        {/* 계정 관리 */}
        <Card className="px-[30px] py-7">
          <div className="mb-[18px] text-[17px] font-extrabold">계정 관리</div>
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <div className="mb-[5px] text-[14.5px] font-extrabold">회원 탈퇴</div>
              <p className="text-[13px] leading-[1.55] text-ink-faint">
                회원 탈퇴 시 모든 데이터가 삭제되며, 복구할 수 없습니다.
              </p>
            </div>
            <button
              onClick={() => setDeleteOpen(true)}
              className="z-btn z-btn-ghost-danger z-btn-md shrink-0"
            >
              회원 탈퇴
            </button>
          </div>
        </Card>
      </div>

      {deleteOpen && (
        <div className="z-backdrop">
          <div className="z-modal max-w-[420px]">
            <h2 className="mb-2.5 text-[19px] font-extrabold">계정을 탈퇴할까요?</h2>
            <p className="mb-[22px] text-sm leading-[1.6] text-ink-muted">
              계정을 탈퇴하면 ZANI에 저장된 개인 정보와 이용 기록을 복구할 수 없습니다.
            </p>
            <div className="flex gap-2.5">
              <button
                onClick={() => setDeleteOpen(false)}
                className="z-btn z-btn-outline flex-1 rounded-[13px] py-[13px]"
              >
                취소
              </button>
              <button
                onClick={() => router.push("/login")}
                className="z-btn z-btn-danger flex-1 rounded-[13px] py-[13px]"
              >
                계정 탈퇴
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
