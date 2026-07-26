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

/** 썸네일 팔레트 (강의 카드) */
export const thumbPalette = [
  { bg: "linear-gradient(135deg,#e8ecff,#f3f0ff)", fg: "#10b981" },
  { bg: "linear-gradient(135deg,#e8f7f1,#f1fbf7)", fg: "#12a870" },
  { bg: "linear-gradient(135deg,#fdf7e2,#fefbef)", fg: "#cba118" },
  { bg: "linear-gradient(135deg,#fde8ee,#fff0f4)", fg: "#d1587a" },
  { bg: "linear-gradient(135deg,#e7edfb,#eff3fd)", fg: "#4a6fd6" },
  { bg: "linear-gradient(135deg,#efe8fb,#f6f1ff)", fg: "#8b6fd0" },
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

const participantsMeta: { id: string; name: string; color: string; host?: boolean }[] = [
  { id: "p0", name: "박서준", color: "#1cdd93", host: true },
  { id: "p1", name: "이지은", color: "#f4c325" },
  { id: "p2", name: "최민서", color: "#21e298" },
  { id: "p3", name: "정하윤", color: "#f26d7d" },
  { id: "p4", name: "강태오", color: "#63e1b0" },
  { id: "p5", name: "윤서아", color: "#3bb0e5" },
  { id: "p6", name: "오지호", color: "#f0803c" },
  { id: "p7", name: "김도현", color: "#65cba4" },
  { id: "p8", name: "이서연", color: "#5b9bd5" },
  { id: "p9", name: "이준호", color: "#c77dff" },
  { id: "p10", name: "박지온", color: "#f4c325" },
  { id: "p11", name: "정민재", color: "#21e298" },
  { id: "p12", name: "한수빈", color: "#e2749b" },
  { id: "p13", name: "강동현", color: "#3bb0e5" },
  { id: "p14", name: "최지우", color: "#8b7bf0" },
  { id: "p15", name: "운서연", color: "#f0803c" },
  { id: "p16", name: "임세훈", color: "#65cba4" },
  { id: "p17", name: "권민아", color: "#5b9bd5" },
];

/** 강의실 참가자 (cam 기본 on, 첫 명만 mic on, 일부 손들기) */
export const participants: Participant[] = participantsMeta.map((p, i) => ({
  ...p,
  cam: true,
  mic: i === 0,
  hand: i === 3 || i === 10,
}));

export const reactionEmojis = ["👍", "❤️", "👏", "🎉", "😮", "🙌"];

export interface ChatMessage {
  id: number;
  author: string;
  text: string;
  host: boolean;
  mine: boolean;
}

export const publicMessages: ChatMessage[] = [
  { id: 1, author: "이지은", text: "안녕하세요!", host: false, mine: false },
  { id: 2, author: "최민서", text: "화면 잘 보입니다 👍", host: false, mine: false },
  { id: 3, author: "박서준", text: "네 시작할게요. 오늘 자료는 채팅에 공유했어요.", host: true, mine: false },
  { id: 4, author: "정하윤", text: "감사합니다!", host: false, mine: false },
];

export const dmMessages: ChatMessage[] = [
  { id: 1, author: "김도현", text: "선생님, 아까 예제 코드 다시 볼 수 있을까요?", host: false, mine: true },
  { id: 2, author: "박서준", text: "네, 종료 후 리포트에 올려둘게요.", host: true, mine: false },
];

/** 강의실 집단 알림 응답 분포 */
export const alertDistribution = [
  { label: "이해함", percent: 58, value: "58%", color: "#21e298" },
  { label: "헷갈림", percent: 32, value: "32%", color: "#f4c325" },
  { label: "잠깐 놓침", percent: 10, value: "10%", color: "#f26d7d" },
];

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

export interface LearnSegment {
  range: string;
  title: string;
  /** 전체(강사 시점) 집중 점수 */
  fAll: number;
  /** 나(학생 시점) 집중 점수 */
  fMine: number;
  evAll: string;
  evMine: string;
  desc: string;
  seek: string;
  mine?: boolean;
}

/** 리포트 타임라인 · 집중 흐름 구간 */
export const learnSegments: LearnSegment[] = [
  { range: "00:00–08:39", title: "상태 관리 개요", fAll: 3, fMine: 4, evAll: "대부분의 수강생이 안정적으로 따라온 도입 구간이에요.", evMine: "도입 개념을 놓치지 않고 꾸준히 집중했어요.", desc: "useState의 역할과 props drilling의 한계를 소개한 안정 구간이에요.", seek: "00:02" },
  { range: "08:40–15:21", title: "Context와 리렌더링", fAll: 2, fMine: 1, evAll: "이해도 알림이 몰린, 전체적으로 집중이 떨어진 구간이에요.", evMine: "리렌더링 원리에서 집중이 크게 흔들렸어요. 다시 볼 것을 추천해요.", desc: "Context 구독과 리렌더링 원리를 다룬, 높은 확인이 필요한 구간이에요.", seek: "08:40" },
  { range: "15:22–23:59", title: "내 질문 · Context 리렌더", fAll: 2, fMine: 1, evAll: "질문이 이어지며 확인이 필요했던 구간이에요.", evMine: "직접 질문을 남길 만큼 어려웠던 구간이에요. 복습이 필요해요.", desc: "내가 공개 질문을 남겼고 확인 필요 상태가 이어진 구간이에요.", seek: "15:22", mine: true },
  { range: "24:00–30:59", title: "useMemo 메모이제이션", fAll: 2, fMine: 2, evAll: "반복 확인 신호가 감지된 핵심 구간이에요.", evMine: "개념은 따라갔지만 집중이 보통 수준이었어요.", desc: "반복된 확인 필요 신호가 감지된 핵심 구간이에요.", seek: "24:10" },
  { range: "31:00–41:59", title: "상태관리 라이브러리 비교", fAll: 3, fMine: 3, evAll: "선택 기준을 다룬, 안정적으로 유지된 구간이에요.", evMine: "비교 설명에 잘 집중했어요.", desc: "라이브러리 선택 기준을 다룬 안정 구간이에요.", seek: "31:05" },
  { range: "42:00–51:19", title: "Zustand 실습", fAll: 4, fMine: 4, evAll: "실습으로 참여도와 집중이 가장 높았던 구간이에요.", evMine: "실습 구간에서 집중이 최고조였어요.", desc: "실습으로 개념을 굳힌 안정 구간이에요.", seek: "42:00" },
  { range: "51:20–74:00", title: "정리와 질문", fAll: 3, fMine: 2, evAll: "핵심을 정리하며 마무리한 구간이에요.", evMine: "마무리 구간에서 집중이 조금 떨어졌어요.", desc: "핵심 개념을 정리하고 마무리한 구간이에요.", seek: "51:20" },
];

/** 리포트(학생) - 복습 추천 */
export const recommendations = [
  { t: "24:10", title: "useMemo 메모이제이션 패턴", reason: "‘헷갈림’ 응답과 같은 개념에서 반복된 확인 필요가 함께 근거가 됐어요.", tag: "헷갈림 · 반복", color: "#f4c325" },
  { t: "08:30", title: "Context API 리렌더링", reason: "‘잠깐 놓침’ 응답과 프롬프트 미응답이 함께 있었어요.", tag: "놓침 · 미응답", color: "#10b981" },
  { t: "31:00", title: "상태관리 라이브러리 비교", reason: "직접 남긴 1:1 질문이 이 개념 설명 구간을 가리켜요.", tag: "내 질문", color: "#15bd7d" },
];

/** 리포트(강사) - 분야별 평가 도넛 */
export const evalDonutData = [
  { name: "전달력", value: 88, color: "#10b981" },
  { name: "구성·흐름", value: 84, color: "#15bd7d" },
  { name: "상호작용", value: 71, color: "#f4c325" },
  { name: "난이도 조절", value: 76, color: "#e0714f" },
];

/** AI 이해도 퀴즈 문제 */
export interface QuizQuestion {
  concept: string;
  t: string;
  q: string;
  opts: string[];
  answer: number;
  explain: string;
}

export const quizData: QuizQuestion[] = [
  { concept: "Context 리렌더링", t: "08:30", q: "Context Provider의 value가 바뀔 때 하위 컴포넌트가 리렌더되는 주된 이유는?", opts: ["상태가 전역이라서", "value 객체의 참조가 매 렌더마다 새로 생겨서", "useEffect가 실행되어서", "key가 바뀌어서"], answer: 1, explain: "객체 리터럴을 value로 넘기면 매 렌더마다 새 참조가 만들어져, 이를 구독하는 하위 컴포넌트가 모두 리렌더됩니다." },
  { concept: "useMemo 최적화", t: "24:10", q: "Provider value의 불필요한 리렌더를 줄이는 가장 적절한 방법은?", opts: ["useState로 감싼다", "value를 useMemo로 메모이즈한다", "컴포넌트를 하나로 합친다", "key를 고정한다"], answer: 1, explain: "value를 useMemo로 감싸 참조를 안정화하면 의존성이 실제로 바뀔 때만 새 참조가 생깁니다." },
  { concept: "Props Drilling", t: "06:40", q: "props drilling에 대한 설명으로 옳은 것은?", opts: ["상태를 전역 저장소에 두는 것", "중간 컴포넌트들이 쓰지 않는 props를 전달만 하는 상황", "props를 삭제하는 최적화", "상태를 지역화하는 패턴"], answer: 1, explain: "실제로 사용하지 않는 중간 계층이 단지 아래로 props를 전달만 하는 구조를 말합니다." },
  { concept: "상태관리 라이브러리", t: "31:00", q: "외부 상태관리 라이브러리 도입을 고려할 만한 상황은?", opts: ["상태가 지역적일 때", "전역성이 크고 미들웨어·비동기 흐름이 필요할 때", "컴포넌트가 하나뿐일 때", "스타일링이 복잡할 때"], answer: 1, explain: "전역 상태가 넓고 미들웨어나 복잡한 비동기 흐름이 필요할 때 라이브러리가 유리합니다." },
  { concept: "useCallback", t: "27:52", q: "useCallback이 실제로 필요한 경우는?", opts: ["모든 함수에 항상", "메모이즈된 자식에 함수를 props로 넘길 때", "상태를 만들 때", "렌더링을 완전히 막을 때"], answer: 1, explain: "React.memo된 자식에게 함수를 props로 넘길 때 참조 안정화를 위해 필요합니다." },
];

/** 강사 리포트 개선 TIP */
export const improveTips = [
  { icon: "📘", color: "#10b981", title: "어려운 구간 보강", obs: "1:20:00~1:40:00 예외 처리 및 응답 코드 구간에서 집중도·이해도가 낮았어요.", tip: "· 추가 예시 코드와 실습 시간을 늘려보세요." },
  { icon: "🙋", color: "#12a870", title: "질문 응답 시간 확보", obs: "질문이 많은 구간에서 응답 시간이 짧아 아쉬움이 있었어요.", tip: "· 중간중간 질문 시간을 명시적으로 확보해보세요." },
  { icon: "📷", color: "#e0455f", title: "시각 자료 활용 강화", obs: "복잡한 개념 설명 시 시각 자료가 있으면 이해도 향상에 도움이 돼요.", tip: "· 다이어그램, 플로우차트 활용을 늘려보세요." },
  { icon: "🎯", color: "#e2b41b", title: "학생 참여 유도", obs: "학생들의 참여가 더 활발해질 수 있어요.", tip: "· 개념 설명 후 간단한 퀴즈나 실습 중간 점검 추천" },
];

/** 강사 리포트 인사이트 */
export const insights = [
  { icon: "🔔", bg: "#ffe7ea", text: "어려움 구간(예외 처리·응답 코드)에서 이해도 알림이 집중적으로 발생했어요." },
  { icon: "🧪", bg: "#ebf8f3", text: "실습 전후 구간의 집중도가 상대적으로 높았습니다." },
  { icon: "📈", bg: "#f0faf6", text: "전반적으로 후반부로 갈수록 집중도가 회복되는 흐름입니다." },
  { icon: "💬", bg: "#fdf8e7", text: "질문이 몰린 구간의 응답 시간이 짧아 아쉬움이 있었어요." },
];

export const instructorGlance = [
  { icon: "👥", iconColor: "#10b981", label: "총 수강생", value: "32명" },
  { icon: "🕐", iconColor: "#10b981", label: "수업 시간", value: "2시간 5분" },
  { icon: "💬", iconColor: "#10b981", label: "채팅 수", value: "184개" },
  { icon: "📈", iconColor: "#12a870", label: "평균 집중도", value: "78%", badge: "보통" },
  { icon: "🔔", iconColor: "#e0455f", label: "이해도 알림 발생", value: "7회" },
];

export const studentGlance = [
  { label: "🎯 평균 집중도", value: "82%" },
  { label: "💬 질문 수", value: "1개" },
  { label: "❓ 헷갈림 표시", value: "2회" },
  { label: "📌 놓침 표시", value: "1회" },
];

export const instructorSummary =
  "이번 수업은 전반적으로 논리적인 흐름과 단계적인 설명이 잘 구성되어 있었고, 프로젝트 구조 설명을 시작으로 의존성 주입, 예외 처리, 테스트 코드 작성까지 자연스럽게 이어져 학습 목표가 잘 달성되었습니다. 질문이 많은 구간에서는 응답 시간이 짧아 아쉬움이 있었지만, 실습 전후 구간의 집중도가 높았고 후반부로 갈수록 집중도와 이해도가 회복되는 경향이 나타났습니다.";

export const studentSummary =
  "전반적으로 높은 집중도와 활발한 참여가 돋보인 수업이었어요. 특히 상태관리 라이브러리 비교 구간에서 깊이 있는 질문을 남겨 이해를 확장했어요. 몇몇 구간에서는 잠깐 놓치거나 헷갈린 순간이 있었지만, 반복 확인과 질문을 통해 스스로 학습을 이어간 점이 인상적이에요.";

/** 요약 레포트 문단 (수업 클립 탭의 AI 요약 문서) */
export const summarySections = [
  { h: "1. 상태 관리의 출발점", p: "useState는 컴포넌트의 지역 상태를 다루기에 적합하지만, 앱 전역에서 공유되는 상태에는 한계가 있습니다. 상태를 상위에서 하위로 props로 계속 전달하다 보면 props drilling 문제가 생기고, 중간 컴포넌트들이 데이터를 전달만 하는 통로가 됩니다." },
  { h: "2. Context API와 리렌더링", p: "Context는 props drilling을 해결하지만, Provider의 value 참조가 바뀔 때마다 이를 구독하는 모든 하위 컴포넌트가 리렌더링됩니다. value로 객체 리터럴을 그대로 넘기면 매 렌더마다 새로운 참조가 만들어져 성능 문제가 발생할 수 있습니다." },
  { h: "3. value 메모이제이션 패턴", p: "이 문제를 피하려면 Provider의 value를 useMemo로 감싸 참조를 안정화합니다. 함수를 함께 내려줄 때는 useCallback으로 함수 참조도 고정합니다. 다만 과도한 메모이제이션은 오히려 코드 복잡도를 높이므로, 실제 병목이 확인된 지점에만 적용하는 것이 좋습니다." },
  { h: "4. 외부 상태 관리 라이브러리", p: "전역성이 크고 미들웨어나 비동기 흐름 제어가 필요하면 Redux, Zustand 같은 외부 라이브러리가 유리합니다. 특히 Zustand는 보일러플레이트가 적어 같은 예제를 훨씬 간결하게 구현할 수 있습니다." },
  { h: "5. 정리와 선택 기준", p: "지역 상태는 useState, 좁은 범위의 공유 상태는 Context, 전역이거나 복잡한 상태 흐름은 라이브러리로 접근합니다. 무엇을 선택하든 리렌더링 비용과 참조 안정성을 이해하는 것이 핵심입니다." },
];

/** 집중 점수(0–4)에 대한 색/배경/라벨 */
export function focusColor(f: number) {
  return f >= 3.5 ? "#16c582" : f >= 2.5 ? "#5bc79d" : f >= 1.5 ? "#f4c325" : f >= 0.5 ? "#e0714f" : "#e0455f";
}
export function focusBg(f: number) {
  return f >= 3.5 ? "#eaf7f2" : f >= 2.5 ? "#eef8ef" : f >= 1.5 ? "#fdf8e7" : f >= 0.5 ? "#fdefe8" : "#fdeeee";
}
export function focusLabel(f: number) {
  return ["매우 낮음", "낮음", "보통", "높음", "매우 높음"][Math.round(f)] ?? "보통";
}
