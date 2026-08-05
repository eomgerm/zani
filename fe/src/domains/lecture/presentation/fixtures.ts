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

/** 리포트 - 수업 내용 전사 */
export const transcript = [
  { t: "00:02", speaker: "박서준", text: "자, 오늘은 React의 상태 관리를 깊이 있게 다뤄보겠습니다." },
  { t: "03:15", speaker: "박서준", text: "useState는 지역 상태에 적합하지만 전역 상태는 다른 접근이 필요해요." },
  { t: "06:40", speaker: "박서준", text: "상태를 여러 단계로 내려주다 보면 props drilling 문제가 생깁니다." },
  { t: "08:40", speaker: "박서준", text: "먼저 Context API의 리렌더링 이슈를 이해해야 합니다." },
  { t: "12:10", speaker: "정하윤", text: "Context랑 Redux는 어떤 기준으로 골라야 하나요?" },
  { t: "12:35", speaker: "박서준", text: "전역성이 크고 미들웨어가 필요하면 라이브러리, 아니면 Context가 낫습니다." },
  { t: "15:22", speaker: "김도현", text: "선생님, Context 값이 바뀌면 왜 하위 전체가 리렌더되나요?" },
  { t: "15:48", speaker: "박서준", text: "좋은 질문이에요. Provider value의 참조가 바뀌기 때문입니다." },
  { t: "19:05", speaker: "박서준", text: "예제 코드로 리렌더가 어디서 발생하는지 확인해볼게요." },
  { t: "24:10", speaker: "박서준", text: "그래서 useMemo로 value를 메모이즈하는 패턴이 나옵니다." },
  { t: "27:30", speaker: "이지은", text: "useCallback도 같이 써야 하나요?" },
  { t: "27:52", speaker: "박서준", text: "함수를 props로 넘길 때만 필요하니 상황에 맞게 쓰면 됩니다." },
  { t: "31:05", speaker: "박서준", text: "다음으로 외부 상태 관리 라이브러리를 비교해볼게요." },
  { t: "42:00", speaker: "박서준", text: "Zustand로 같은 예제를 다시 구현하면 훨씬 간결해집니다." },
  { t: "51:20", speaker: "박서준", text: "정리하고 질문 받겠습니다. 오늘 자료는 리포트에 함께 올려둘게요." },
];

/* 강사 리포트(분야별 평가·수업 인사이트·한눈에 보기·종합 피드백) fixture 는 110 이,
   학생 리포트(한눈에 보기·참여 요약·복습 추천) fixture 는 297 이 걷어냈다. 값은 각각
   GET /reports/instructor 와 /reports/student 가 준다 — 라벨·색·설명만 화면이 갖고 있다. */

// AI 이해도 퀴즈 fixture(`quizData`)도 걷어냈다. 문항·보기·해설은 249 가 만들고
// QuizScreen 이 useStudentQuiz 로 받는다.

// 수업 클립 탭의 AI 요약 문서 fixture(`summarySections`)는 걷어냈다. 그 자리는 이제
// SessionSummaryCard 가 `GET /api/v1/sessions/{sessionId}/reports/summary` 로 채운다.
// 서버가 만드는 것은 5절 구조가 아니라 문단 하나다(S15P11A105-302).

