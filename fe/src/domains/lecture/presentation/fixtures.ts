/** 강의 처리 상태 */
export type LectureStatus = "LIVE" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface Lecture {
  id: string;
  title: string;
  date: string;
  role: "instructor" | "student";
  status: LectureStatus;
  dur: string;
  students?: number;
  instructor?: string;
}

/** 내 강의실/리포트에서 사용하는 강의 목록 (프로토타입 시드) */
export const lectures: Lecture[] = [
  { id: "s1", title: "React 상태관리 심화", date: "2026-07-14", role: "instructor", status: "COMPLETED", students: 24, dur: "1시간 32분" },
  { id: "s2", title: "알고리즘 문제풀이 Live", date: "2026-07-15", role: "instructor", status: "COMPLETED", students: 18, dur: "58분" },
  { id: "s3", title: "Spring Boot REST API", date: "2026-07-16", role: "instructor", status: "PROCESSING", students: 30, dur: "2시간 5분" },
  { id: "s4", title: "CS 네트워크 기초", date: "2026-07-10", role: "student", status: "COMPLETED", instructor: "박서준", dur: "1시간 12분" },
  { id: "s5", title: "데이터베이스 정규화", date: "2026-07-08", role: "student", status: "COMPLETED", instructor: "이지은", dur: "47분" },
  { id: "s6", title: "JavaScript 비동기 마스터", date: "2026-07-16", role: "student", status: "LIVE", instructor: "최민서", dur: "진행 중" },
  { id: "s7", title: "운영체제 스케줄링", date: "2026-07-12", role: "student", status: "FAILED", instructor: "정하윤", dur: "54분" },
  { id: "s8", title: "Docker 실전 배포", date: "2026-07-13", role: "instructor", status: "FAILED", students: 22, dur: "1시간 20분" },
];

export interface Participant {
  id: string;
  name: string;
  color: string;
  host?: boolean;
  cam: boolean;
  mic: boolean;
  hand: boolean;
}

// 어두운 스테이지 위 원형 아바타에 올라가므로 채도 낮은 팔레트를 쓴다(프로토타입 participantsMeta).
const participantsMeta: { id: string; name: string; color: string; host?: boolean }[] = [
  { id: "p0", name: "박서준", color: "#10b981", host: true },
  { id: "p1", name: "이지은", color: "#c9a24b" },
  { id: "p2", name: "최민서", color: "#2aa584" },
  { id: "p3", name: "정하윤", color: "#c07284" },
  { id: "p4", name: "강태오", color: "#57ad97" },
  { id: "p5", name: "윤서아", color: "#5e9ec6" },
  { id: "p6", name: "오지호", color: "#c88d5d" },
  { id: "p7", name: "김도현", color: "#66b195" },
  { id: "p8", name: "이서연", color: "#6d8fc2" },
  { id: "p9", name: "이준호", color: "#9c87cc" },
  { id: "p10", name: "박지온", color: "#c9a24b" },
  { id: "p11", name: "정민재", color: "#2aa584" },
  { id: "p12", name: "한수빈", color: "#b981a0" },
  { id: "p13", name: "강동현", color: "#5e9ec6" },
  { id: "p14", name: "최지우", color: "#8681c8" },
  { id: "p15", name: "운서연", color: "#c88d5d" },
  { id: "p16", name: "임세훈", color: "#66b195" },
  { id: "p17", name: "권민아", color: "#6d8fc2" },
  { id: "p18", name: "오지훈", color: "#9c87cc" },
  { id: "p19", name: "김나영", color: "#10b981" },
  { id: "p20", name: "문지후", color: "#c9a24b" },
  { id: "p21", name: "서하늘", color: "#2aa584" },
  { id: "p22", name: "조현우", color: "#b981a0" },
  { id: "p23", name: "배수연", color: "#5e9ec6" },
];

/** 강의실 참가자 (cam 기본 on, 첫 명만 mic on, 일부 손들기) */
export const participants: Participant[] = participantsMeta.map((p, i) => ({
  ...p,
  cam: true,
  mic: i === 0,
  hand: i === 3 || i === 10,
}));

/** 갤러리 타일이 쓰는 형태로 변환한 시연용 참가자 목록 */
export const participantTiles = participants.map((p) => ({
  id: p.id,
  name: p.name,
  color: p.color,
  role: p.host ? ("instructor" as const) : ("student" as const),
  cameraEnabled: p.cam,
  microphoneEnabled: p.mic,
  handRaised: p.hand,
}));

/* 수업 클립 탭의 전사 fixture(`transcript`)는 308 이 강사용 목업과 함께 걷어냈다. 녹화·전사는
   학생이 GET /reports/student, 강사가 GET /reports/instructor/clip 로 받는다 — 화면에 박제된 남의 수업
   문장이 실제 수업으로 읽히는 일이 없어야 한다. */

/* 강사 리포트(분야별 평가·수업 인사이트·한눈에 보기·종합 피드백) fixture 는 110 이,
   학생 리포트(한눈에 보기·참여 요약·복습 추천) fixture 는 297 이 걷어냈다. 값은 각각
   GET /reports/instructor 와 /reports/student 가 준다 — 라벨·색·설명만 화면이 갖고 있다. */

// AI 이해도 퀴즈 fixture(`quizData`)도 걷어냈다. 문항·보기·해설은 249 가 만들고
// QuizScreen 이 useStudentQuiz 로 받는다.

// 수업 클립 탭의 AI 요약 문서 fixture(`summarySections`)는 걷어냈다. 그 자리는 이제
// SessionSummaryCard 가 `GET /api/v1/sessions/{sessionId}/reports/summary` 로 채운다.
// 서버가 만드는 것은 5절 구조가 아니라 문단 하나다(S15P11A105-302).

