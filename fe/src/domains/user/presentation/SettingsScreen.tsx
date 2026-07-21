"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { color } from "@/shared/lib/theme";
import { Avatar, Card, MOCK_USER } from "@/shared/ui";

function Toggle({ on, onClick }: { on: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      style={{
        width: 48,
        height: 28,
        borderRadius: 999,
        border: "none",
        cursor: "pointer",
        background: on ? color.primary : "#d7dbee",
        position: "relative",
        flexShrink: 0,
        transition: "background .15s",
      }}
    >
      <span
        style={{
          position: "absolute",
          top: 3,
          left: on ? 23 : 3,
          width: 22,
          height: 22,
          borderRadius: "50%",
          background: "#fff",
          transition: "left .15s",
          boxShadow: "0 1px 3px rgba(0,0,0,.2)",
        }}
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
  icon: string;
  title: string;
  desc: string;
  on: boolean;
  onToggle: () => void;
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 14,
        padding: "16px 0",
        borderTop: `1px solid ${color.borderLight}`,
      }}
    >
      <span
        style={{
          width: 38,
          height: 38,
          borderRadius: 11,
          background: color.primarySoft,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontSize: 17,
        }}
      >
        {icon}
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 800, fontSize: 14.5 }}>{title}</div>
        <div style={{ fontSize: 12.5, color: color.textFainter, marginTop: 3, lineHeight: 1.5 }}>{desc}</div>
      </div>
      <Toggle on={on} onClick={onToggle} />
    </div>
  );
}

/**
 * SC-07 계정 설정. 프로필 · 알림 설정 · 계정 관리(회원 탈퇴).
 * 이름 편집·토글·탈퇴는 시연용 로컬 상태로만 동작한다.
 */
export function SettingsScreen() {
  const router = useRouter();
  const [name, setName] = useState<string>(MOCK_USER.name);
  const [notifSchedule, setNotifSchedule] = useState(true);
  const [notifReport, setNotifReport] = useState(true);
  const [deleteOpen, setDeleteOpen] = useState(false);

  return (
    <>
      <h1 style={{ fontSize: 26, fontWeight: 800, margin: "0 0 4px", letterSpacing: "-.5px" }}>계정 설정</h1>
      <p style={{ color: color.textMuted, margin: "0 0 26px" }}>프로필과 알림을 관리해요.</p>

      <div style={{ maxWidth: 1180, display: "flex", flexDirection: "column", gap: 20 }}>
        {/* 프로필 정보 */}
        <Card padding="28px 30px">
          <div style={{ fontWeight: 800, fontSize: 17, marginBottom: 20 }}>프로필 정보</div>
          <div style={{ display: "flex", alignItems: "flex-start", gap: 34, flexWrap: "wrap" }}>
            <Avatar initial={name.charAt(0)} size={84} style={{ fontSize: 34 }} />
            <div style={{ flex: 1, minWidth: 260, display: "flex", gap: 22, flexWrap: "wrap" }}>
              <div style={{ flex: 1, minWidth: 200 }}>
                <label style={{ display: "block", fontSize: 13, color: color.textFaint, fontWeight: 700, marginBottom: 7 }}>
                  이름
                </label>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  style={{
                    width: "100%",
                    padding: "12px 14px",
                    border: `1px solid ${color.borderMuted}`,
                    borderRadius: 11,
                    fontSize: 14.5,
                    outline: "none",
                    fontFamily: "inherit",
                    color: color.text,
                    boxSizing: "border-box",
                  }}
                />
              </div>
              <div style={{ flex: 1, minWidth: 200 }}>
                <label style={{ display: "block", fontSize: 13, color: color.textFaint, fontWeight: 700, marginBottom: 7 }}>
                  이메일 (변경 불가)
                </label>
                <div
                  style={{
                    padding: "12px 14px",
                    border: `1px solid ${color.borderLight}`,
                    borderRadius: 11,
                    background: color.surfaceMuted,
                    color: color.textFaint,
                    fontSize: 14,
                    boxSizing: "border-box",
                  }}
                >
                  {MOCK_USER.email}
                </div>
              </div>
            </div>
          </div>
          <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 20 }}>
            <button
              style={{
                padding: "12px 24px",
                borderRadius: 12,
                border: "none",
                background: color.primary,
                color: "#fff",
                fontWeight: 800,
                cursor: "pointer",
                fontFamily: "inherit",
                fontSize: 14,
              }}
            >
              저장하기
            </button>
          </div>
        </Card>

        {/* 알림 설정 */}
        <Card padding="28px 30px">
          <div style={{ fontWeight: 800, fontSize: 17, marginBottom: 6 }}>알림 설정</div>
          <p style={{ color: color.textFaint, fontSize: 13.5, margin: "0 0 18px", lineHeight: 1.55 }}>
            이메일로 알림을 받아보실 수 있습니다.
          </p>
          <NotifRow
            icon="📅"
            title="수업 일정 알림"
            desc="예약된 수업 일정이 시작되기 전에 알림을 받습니다."
            on={notifSchedule}
            onToggle={() => setNotifSchedule((v) => !v)}
          />
          <NotifRow
            icon="📄"
            title="강의 리포트 알림"
            desc="수업 리포트가 생성되면 이메일로 알림을 받습니다."
            on={notifReport}
            onToggle={() => setNotifReport((v) => !v)}
          />
        </Card>

        {/* 계정 관리 */}
        <Card padding="28px 30px">
          <div style={{ fontWeight: 800, fontSize: 17, marginBottom: 18 }}>계정 관리</div>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 16,
              flexWrap: "wrap",
            }}
          >
            <div>
              <div style={{ fontWeight: 800, fontSize: 14.5, marginBottom: 5 }}>회원 탈퇴</div>
              <p style={{ color: color.textFaint, fontSize: 13, margin: 0, lineHeight: 1.55 }}>
                회원 탈퇴 시 모든 데이터가 삭제되며, 복구할 수 없습니다.
              </p>
            </div>
            <button
              onClick={() => setDeleteOpen(true)}
              style={{
                padding: "12px 22px",
                borderRadius: 12,
                border: "1px solid #f0aeb8",
                background: "#fff",
                color: color.red,
                fontWeight: 800,
                cursor: "pointer",
                fontSize: 14,
                flexShrink: 0,
              }}
            >
              회원 탈퇴
            </button>
          </div>
        </Card>
      </div>

      {deleteOpen && (
        <div
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(28,32,58,.42)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            padding: 24,
            zIndex: 100,
          }}
        >
          <div
            style={{
              width: "100%",
              maxWidth: 420,
              background: "#fff",
              borderRadius: 20,
              padding: "26px 28px",
              boxShadow: "0 24px 60px rgba(28,32,58,.35)",
              animation: "zPop .18s",
            }}
          >
            <h2 style={{ fontSize: 19, fontWeight: 800, margin: "0 0 10px" }}>계정을 탈퇴할까요?</h2>
            <p style={{ color: color.textMuted, margin: "0 0 22px", fontSize: 14, lineHeight: 1.6 }}>
              계정을 탈퇴하면 ZANI에 저장된 개인 정보와 이용 기록을 복구할 수 없습니다.
            </p>
            <div style={{ display: "flex", gap: 10 }}>
              <button
                onClick={() => setDeleteOpen(false)}
                style={{
                  flex: 1,
                  padding: 13,
                  borderRadius: 13,
                  border: `1px solid ${color.borderMuted}`,
                  background: "#fff",
                  color: color.textMuted,
                  fontWeight: 800,
                  cursor: "pointer",
                  fontFamily: "inherit",
                }}
              >
                취소
              </button>
              <button
                onClick={() => router.push("/login")}
                style={{
                  flex: 1,
                  padding: 13,
                  borderRadius: 13,
                  border: "none",
                  background: color.red,
                  color: "#fff",
                  fontWeight: 800,
                  cursor: "pointer",
                  fontFamily: "inherit",
                }}
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
