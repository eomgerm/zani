# ZANI

> **AI 기반 실시간 학습 반응 분석 및 맞춤 복습 서비스**

ZANI는 실시간 온라인 강의에서 학생의 비언어적 반응과 학습 데이터를 분석하여  
강사에게는 수업 운영을 위한 인사이트를, 학생에게는 강의 요약과 맞춤형 복습 자료를 제공하는 서비스입니다.

<br>

## 프로젝트 개요

### 프로젝트명

**ZANI**

### 프로젝트 기간

- 2026.07.06 ~ 2026.08.14

### 기획 배경

비대면 강의에서는 강사가 학생들의 표정과 반응을 한눈에 확인하기 어렵습니다.  
학생도 이해하지 못한 내용을 즉시 표현하기 어렵고, 수업이 끝난 뒤 어떤 부분을 다시 학습해야 하는지 판단하기 쉽지 않습니다.

ZANI는 실시간 강의 중 발생하는 학생의 반응을 AI로 분석하고, 수업 종료 후 강의 내용과 학습 반응을 함께 정리하여 강사와 학생 모두에게 필요한 정보를 제공합니다.

### 핵심 목표

- 학생의 비언어적 반응을 분석하여 강사가 수업 흐름을 빠르게 파악할 수 있도록 지원
- 집중 저하, 혼란, 졸음, 자리 이탈 등 주요 학습 상태를 수업 구간별로 분석
- 강의 음성을 자동으로 전사하고 핵심 내용과 강조 구간을 요약
- 학생별 학습 반응을 기반으로 맞춤형 복습 콘텐츠 제공
- 강사의 수업 개선과 학생의 자기주도 학습을 동시에 지원

<br>

## 주요 사용자

| 사용자 | 주요 목적 |
| --- | --- |
| 강사 | 실시간 학생 반응 확인, 수업 진행 조절, 수업 종료 후 강의 분석 확인 |
| 학생 | 실시간 강의 참여, 강의 요약 확인, 부족한 구간 복습 |

<br>

## 주요 기능

### 실시간 강의

- WebRTC 기반 실시간 화상 강의
- LiveKit SFU 기반 다자간 영상 송수신
- 화면 공유
- 전체/1:1 채팅
- 강의 참여자 관리
- 강의 자동 녹화 및 다시보기

### 실시간 학습 반응 분석

- MediaPipe 기반 얼굴 및 자세 특징 추출
- 학생의 학습 상태 분석 (이해도, 자리 이탈 등)
- 시간대별 반응 변화 기록
- 다수 학생에게 동일한 반응이 감지될 경우 강사에게 알림
- 강사가 학생 상태를 확인할 수 있는 실시간 대시보드 제공

> 학생의 표정만으로 이해도를 단정하지 않고, 일정 시간 동안 반복되는 반응과 수업 참여 데이터를 함께 활용합니다.

### 강의 음성 분석

- Whisper 기반 강의 음성 전사
- 강의 전체 요약
- 핵심 키워드 추출
- 강사가 강조한 내용 정리
- 주요 구간 타임스탬프 생성
- 다시보기 영상과 전사 내용 연동

### 강사 리포트

- 전체 학생 반응 요약
- 시간대별 집중도 및 반응 변화
- 학생들이 어려워한 것으로 추정되는 구간
- 반응이 좋았던 구간
- 수업 중 주요 이벤트 기록
- 다음 강의를 위한 개선 포인트 제공

### 학생 맞춤 복습

- 강의 핵심 내용 요약
- 이해가 부족했던 것으로 추정되는 구간 추천
- 반복 시청이 필요한 영상 구간 제공
- 복습 퀴즈 및 학습 노트 제공
- 개인별 학습 기록 확인

<br>

## 서비스 흐름

### 강사

```text
강의 생성
→ 실시간 강의 진행
→ 학생 반응 실시간 확인
→ 필요 시 추가 설명 또는 상태 확인
→ 강의 종료
→ 강의 분석 리포트 확인
```

### 학생

```text
강의 입장
→ 실시간 수업 참여
→ 학습 반응 및 참여 데이터 기록
→ 강의 종료
→ 강의 요약 확인
→ 맞춤 복습 구간 학습
```

<br>

## AI 처리 흐름

```text
학생 영상 입력
→ MediaPipe 특징 추출
→ 일정 시간 단위 반응 분석
→ 수업 구간별 상태 집계
→ 강사 대시보드 및 수업 리포트 반영
```

```text
강의 음성 및 녹화 영상
→ Whisper 음성 전사
→ 강의 내용 구간화
→ 핵심 내용 및 강조 구간 분석
→ 강의 요약·복습 자료·퀴즈 생성
```

<br>

## 기술 스택

### Frontend

| 기술 | 사용 목적 |
| --- | --- |
| Next.js | 웹 애플리케이션 개발 |
| TypeScript | 정적 타입 기반 안정성 확보 |
| React | UI 컴포넌트 구성 |
| WebRTC | 실시간 영상·음성 통신 |
| LiveKit SDK | 실시간 강의방 및 미디어 연결 |

### Backend

| 기술 | 사용 목적 |
| --- | --- |
| Java | 백엔드 개발 언어 |
| Spring Boot | REST API 및 비즈니스 로직 구현 |
| Spring Security | 인증 및 인가 |
| MySQL | 사용자, 강의, 학습 기록 저장 |
| Redis | 실시간 상태 및 임시 데이터 관리 |

### AI

| 기술 | 사용 목적 |
| --- | --- |
| Python | AI 분석 서버 및 전처리 |
| MediaPipe | 얼굴·자세 특징 추출 |
| Whisper | 강의 음성 전사 |
| SSAFY GMS | 생성형 AI 기반 요약 및 학습 콘텐츠 생성 |

### Realtime & Infrastructure

| 기술 | 사용 목적 |
| --- | --- |
| LiveKit SFU | 다자간 실시간 미디어 중계 |
| LiveKit Egress | 강의 녹화 |
| Docker | 실행 환경 컨테이너화 |
| AWS EC2 | 서비스 배포 |
| Amazon RDS for MySQL | 데이터베이스 운영 |
| GitLab CI/CD | 빌드 및 배포 자동화 |

### Collaboration

| 도구 | 사용 목적 |
| --- | --- |
| GitLab | 소스 코드 및 Merge Request 관리 |
| Jira | 스프린트 및 이슈 관리 |
| Notion | 회의록, 기획서, 컨벤션 문서화 |
| Figma | 와이어프레임 및 UI 디자인 |

<br>

## 실행 방법

### 사전 준비

- Java 21
- Node.js
- Python
- MySQL
- Docker
- LiveKit 계정 또는 로컬 서버

### 저장소 복제

```bash
git clone <GITLAB_REPOSITORY_URL>
cd ZANI
```

### Frontend

```bash
cd frontend
npm install
npm run dev
```

### Backend

```bash
cd backend

# Gradle 사용 시
./gradlew bootRun

# Windows
gradlew.bat bootRun
```

### AI Server

```bash
cd ai
python -m venv .venv

# macOS / Linux
source .venv/bin/activate

# Windows
.venv\Scripts\activate

pip install -r requirements.txt
python -m app
```

### Docker

```bash
docker compose up -d
```

> 실행 명령은 각 모듈의 실제 설정이 확정되면 해당 README와 함께 갱신합니다.

<br>

## 문서화 규칙

프로젝트 진행 과정에서 생성되는 문서는 `Notion`과 GitLab의 `docs/` 디렉터리에 정리합니다.

| 문서 | 관리 위치 |
| --- | --- |
| 프로젝트 기획서 | Notion |
| 요구사항 명세서 | Notion / `docs/requirements` |
| 기능 명세서 | Notion / `docs/specification` |
| API 명세서 | `docs/api` |
| ERD | `docs/erd` |
| 시스템 아키텍처 | `docs/architecture` |
| Git 및 Coding Convention | `docs/convention` |
| 회의록 | Notion / `docs/meeting` |
| 트러블슈팅 | `docs/troubleshooting` |
| 회고 | Notion |

### 문서 작성 원칙

- 중요한 의사결정은 결정 배경과 함께 기록합니다.
- 문제 상황, 원인, 해결 과정, 결과를 트러블슈팅 문서에 작성합니다.
- 기능 변경 시 관련 문서도 함께 갱신합니다.
- 이미지와 다이어그램에는 설명을 추가합니다.
- 문서 최종 수정일과 작성자를 표시합니다.

<br>

## 프로젝트 문서

- **Notion**  
  https://periwinkle-ostrich-9de.notion.site/396a48a2c68680d8a023d35a50a7c45b?source=copy_link
- **Jira**  
  https://ssafy.atlassian.net/jira/software/c/projects/S15P11A105/
- **GitLab**  
  https://lab.ssafy.com/s15-webmobile1-sub1/S15P11A105

<br>

## 팀원

박상은 (팀장)
김용휘
김태정
양지훈
엄기훈

<br>

## 프로젝트 관리 원칙

- 모든 작업은 Jira 이슈를 기반으로 진행합니다.
- 회의 결과와 주요 의사결정은 Notion에 기록합니다.
- 개발 과정에서 발생한 문제는 트러블슈팅 문서로 남깁니다.
- 기능 구현 전 요구사항과 완료 조건을 명확하게 정의합니다.
- Merge Request를 통해 코드 리뷰 후 병합합니다.
- README는 프로젝트 진행 상황에 맞춰 지속적으로 갱신합니다.
