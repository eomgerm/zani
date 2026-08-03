// report 도메인의 공개 API. 다른 도메인/app에서는 이 파일을 통해서만 접근한다.
// infrastructure(HTTP 어댑터)는 공개하지 않는다. 메모 확정처럼 되돌릴 수 없는 조작은
// 확인 절차를 가진 presentation 컴포넌트(InstructorNoteEditor)를 통해서만 실행되어야 한다.
export { InstructorNoteEditor } from "./presentation/InstructorNoteEditor";
