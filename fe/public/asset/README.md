# ZANI 랜딩페이지 데모 미디어

`npm run record:landing`을 실행하면 개인정보가 없는 데모 응답으로 실제 ZANI 강의실·프롬프트·코칭 카드·리포트 화면을 자동 조작하고 아래 사진과 영상을 다시 생성합니다.

- `landing-live-classroom.png`
- `landing-browser-analysis.png`
- `during-student-prompt.png`
- `during-instructor-tip.png`
- `after-instructor-report.png`
- `after-student-report.png`
- `after-student-quiz-result.png`

수업 이후 리포트 캡처는 `/` 랜딩페이지에서 확대·이동 애니메이션으로 재생됩니다. 강의실 캡처 화면은 `src/app/dev/landing-recording/[scene]`에서 만들며, 녹화 동작 순서는 `recording/landing-demos.spec.ts`에서 관리합니다.
