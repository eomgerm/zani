# DDD 에이전트 개발 지침 설계

## 1. 목적

ZANI 백엔드에서 작업하는 에이전트가 동일한 DDD 개발 스타일을 유지하도록 저장소 로컬 지침을 만든다.

지침은 Notion의 `실용적 DDD 기반 Spring Boot 패키지 아키텍처 가이드`를 기준으로 다음 항목만 규정한다.

- 도메인 중심 패키지 구조
- 계층별 책임과 의존 방향
- 경량 Command/Query 분리
- UseCase와 Application Service 구성
- 순수 도메인 모델과 JPA Entity의 경계
- Repository, Port, Adapter, Mapper의 위치
- 도메인 간 호출 방식
- 공통 코드의 범위
- 계층별 테스트 원칙

기능별 상세 설계는 이 지침의 범위에 포함하지 않는다. 각 기능을 구현할 때 여기서 정의하는 DDD 경계와 의존 규칙만 적용한다.

## 2. 현재 프로젝트

- Java 21
- Spring Boot 4.1
- Gradle 단일 모듈
- 루트 패키지: `com.a105.zani`
- 현재는 애플리케이션 시작 클래스와 기본 Context 테스트만 존재한다.
- 기존 `AGENTS.md`와 아키텍처 지침 문서는 없다.

## 3. 산출물

### 3.1 루트 `AGENTS.md`

에이전트가 모든 작업에서 즉시 적용할 수 있는 짧고 강한 규칙을 한국어로 작성한다.

포함 내용:

- 도메인 중심 패키지 구성
- 계층별 허용 의존성
- Command/Query 판단 기준
- 상태 변경과 도메인 불변식 규칙
- UseCase, Service, Repository, Port, Adapter, Mapper 네이밍과 위치
- JPA Entity와 도메인 모델 분리
- 도메인 간 접근 제한
- `common`과 `util` 제한
- 테스트 배치와 검증 기준
- 불필요한 추상화를 만들지 않는 YAGNI 규칙

상세 설명은 아키텍처 문서로 연결한다.

### 3.2 `docs/architecture/ddd-development-guide.md`

`AGENTS.md` 규칙의 근거와 예시를 한국어로 작성한다.

포함 내용:

- 전체 패키지 트리
- 계층별 책임
- 의존 방향
- Command와 Query 처리 흐름
- UseCase와 Service 구성 예시
- 도메인 모델과 JPA Entity 저장 흐름
- 조회 전용 Row 사용 기준
- Port와 Adapter 사용 기준
- 도메인 간 호출 규칙
- 테스트 구조
- 피해야 할 구조
- 코드 리뷰 체크리스트

## 4. 아키텍처

### 4.1 도메인 중심 모듈러 모놀리스

`com.a105.zani` 아래의 최상위 패키지는 기술 계층이 아닌 업무 도메인으로 나눈다.

```text
com.a105.zani
├── ZaniBeApplication.java
├── common
├── domainA
├── domainB
└── domainC
```

각 업무 도메인은 필요한 책임만 다음 구조로 배치한다.

```text
domainA
├── presentation
│   ├── controller
│   ├── request
│   └── response
├── application
│   ├── command
│   ├── query
│   └── port
├── domain
│   ├── model
│   ├── repository
│   ├── policy
│   └── exception
└── infrastructure
    ├── persistence
    │   ├── entity
    │   ├── mapper
    │   ├── repository
    │   └── query
    └── externalSystem
        ├── client
        ├── config
        └── adapter
```

구조를 맞추기 위한 빈 패키지는 만들지 않는다.

### 4.2 계층별 책임

#### presentation

- HTTP 요청 수신과 검증
- Request를 Command 또는 Query로 변환
- Application Result를 API Response로 변환
- HTTP에만 필요한 표현 관리
- 구체 Service가 아닌 Application UseCase에 의존

#### application

- UseCase 실행 흐름 조정
- 트랜잭션 경계 관리
- 도메인 모델, Repository 계약, 외부 Port 호출
- 비즈니스 규칙 자체보다 실행 순서와 협력 관계 관리

#### domain

- Aggregate, Entity, Value Object
- 비즈니스 불변식과 상태 전이
- 도메인 Repository 인터페이스
- 여러 도메인 객체에 걸친 순수 Policy
- Spring, JPA, HTTP, Feign에 의존하지 않는 순수 Java

#### infrastructure

- JPA Entity와 Spring Data JPA
- 도메인 Repository 구현 Adapter
- JPQL, QueryDSL 등 조회 구현
- 외부 시스템 Client와 Adapter
- 프레임워크와 기술 세부사항

### 4.3 의존 방향

```text
presentation -> application -> domain
                    ^
             infrastructure
```

- Domain은 application, presentation, infrastructure를 알지 못한다.
- Application은 domain에 의존한다.
- Presentation은 application의 UseCase 인터페이스에 의존한다.
- Infrastructure는 domain 또는 application이 정의한 계약을 구현한다.
- Application과 domain은 infrastructure 구현체를 직접 참조하지 않는다.
- 다른 도메인의 JPA Entity 또는 infrastructure Repository를 직접 참조하지 않는다.

## 5. 경량 Command/Query 분리

Command와 Query는 하나의 애플리케이션과 하나의 데이터베이스 안에서 요청 목적을 구분하는 규칙이다. 별도 Read DB, 메시지 버스, 완전한 CQRS를 의미하지 않는다.

### 5.1 Command

- 생성, 수정, 삭제 또는 도메인 상태 전이를 수행한다.
- Application UseCase와 Service를 통한다.
- Application 경계에 `@Transactional`을 적용한다.
- 도메인 모델을 불러와 도메인 메서드로 상태를 변경한다.
- 도메인 Repository 계약을 통해 저장한다.
- 의미 있는 입력이 없으면 Command 객체를 만들지 않는다.
- 반환할 값이 없으면 Result 객체를 만들지 않는다.

### 5.2 Query

- 애플리케이션 상태를 변경하지 않는다.
- 필요한 경우 `@Transactional(readOnly = true)`를 사용한다.
- 단순 조회는 Spring Data JPA 또는 JPQL로 처리할 수 있다.
- 동적 조건, 복잡한 조인, 집계, 페이징, 리포트에만 전용 Query Repository와 Infrastructure Row를 고려한다.
- 단순 조회에 QueryDSL을 강제하지 않는다.
- Application Result는 QueryDSL 어노테이션에 의존하지 않는다.

### 5.3 UseCase와 Service

- UseCase는 Application이 외부에 제공하는 좁은 인터페이스이며 일반적으로 하나의 동작을 가진다.
- 같은 Aggregate를 다루고 의존성이 비슷한 UseCase는 하나의 책임 있는 Service가 함께 구현할 수 있다.
- UseCase와 Service를 기계적으로 1:1로 만들지 않는다.
- `Impl`보다 `ResourceLifecycleService`처럼 책임을 나타내는 이름을 사용한다.
- 책임이나 의존성이 서로 달라지면 Service를 분리한다.

## 6. 도메인 모델과 영속성

```text
Application Service
-> Domain Model
-> Domain Repository
-> Persistence Adapter
-> Persistence Mapper
-> JPA Entity
-> Spring Data JPA Repository
```

- 도메인 모델에 `@Entity`, `@Table`, `@Column`을 붙이지 않는다.
- 모든 상태 변경은 도메인 모델 메서드를 통과한다.
- JPA Entity와 지연 로딩은 infrastructure 내부에 둔다.
- Mapper가 도메인 모델과 JPA Entity를 명시적으로 변환한다.
- 공통 생성일과 수정일은 `common.infrastructure.persistence.BaseJpaEntity`에 둘 수 있다.
- Domain Repository 인터페이스는 domain에 두고 Persistence Adapter가 구현한다.

## 7. 조회 경로

- 단순 Aggregate 조회는 Entity를 Domain Model로 변환한 뒤 Result로 변환할 수 있다.
- 목록, 검색, 집계, 리포트는 Aggregate 전체 로딩을 강제하지 않는다.
- 복잡한 조회는 Infrastructure 전용 Row를 거쳐 Application Result로 변환할 수 있다.
- Query Repository 인터페이스는 application에 둔다.
- Query Repository Adapter와 Row는 infrastructure에 둔다.
- JPQL `select new`와 QueryDSL Projection은 Infrastructure Row만 대상으로 한다.

## 8. Port와 Adapter

- Port는 Application이 외부 시스템에 요구하는 기능의 계약이다.
- Adapter는 Port를 특정 기술로 구현한다.
- 외부 Request와 Response 타입은 infrastructure 밖으로 유출하지 않는다.
- Adapter가 Application 입력을 외부 Request로 변환하고 외부 Response를 내부 객체로 변환한다.
- 외부 오류는 Adapter에서 애플리케이션 예외로 변환한다.
- 외부 연동이 없다면 Port와 Adapter 패키지를 만들지 않는다.

## 9. Domain Policy

- 단일 객체가 처리할 수 있는 규칙은 해당 도메인 모델에 둔다.
- 여러 도메인 객체에 걸친 순수 규칙이 필요할 때만 Policy를 사용한다.
- 기본 구조에서는 `domain.service` 패키지를 만들지 않는다.
- Policy에는 일반적으로 Spring `@Service`를 붙이지 않는다.

## 10. 도메인 간 호출

- 다른 도메인의 infrastructure, JPA Entity, Repository 구현체에 직접 접근하지 않는다.
- 동기 호출은 상대 도메인이 공개한 Application UseCase를 사용한다.
- 다른 도메인의 도메인 모델을 직접 수정하지 않는다.
- 이벤트는 비동기 처리 또는 결합도 완화가 실제로 필요할 때 도입한다.
- 모든 도메인 호출을 처음부터 이벤트로 만들지 않는다.

## 11. Common 패키지

`common`은 여러 도메인이 공유하는 기술 설정과 공통 계약만 가진다.

허용:

- 프레임워크 설정
- 여러 도메인이 공유하는 기술 기반 코드
- 공통 응답 계약
- 전역 예외 처리와 안정적인 오류 분류
- JPA Auditing Base Entity
- 공통 기술 설정

금지:

- 특정 도메인의 Request와 Response
- 도메인 Repository와 Service
- 특정 도메인에서만 사용하는 외부 Client
- 두 곳에서 사용된다는 이유만으로 이동한 비즈니스 모델
- 소유자가 불분명한 거대한 `util` 패키지

## 12. 테스트

- Domain: Spring 없는 순수 단위 테스트
- Application: Repository와 Port를 Mock 또는 Fake로 대체
- Presentation: `@WebMvcTest`
- Persistence: `@DataJpaTest`
- 외부 Adapter: 필요할 때 Mock Server 또는 계약 테스트
- Architecture: 실제 경계가 생긴 뒤 가치가 있을 때만 ArchUnit 도입

테스트 패키지는 운영 코드의 도메인 구조를 따른다.

## 13. 피해야 할 구조

- 최상위 `controller`, `service`, `repository` 중심 수평 패키지
- 모든 코드를 모으는 거대한 `common` 또는 `util`
- 도메인 모델에 JPA와 HTTP 책임 혼합
- 다른 도메인의 Repository와 Entity 직접 참조
- 모든 UseCase를 하나의 Service에 모으는 God Service
- 모든 UseCase마다 Service를 하나씩 만드는 기계적 분리
- 단순 조회까지 QueryDSL 사용
- 복잡한 조회를 위해 여러 Aggregate 전체 로딩
- Application Result의 QueryDSL 의존
- 외부 시스템 DTO가 Application 또는 Domain으로 유출
- 실제 기능이 없는 빈 패키지와 추상화

## 14. 수용 기준

최종 지침은 다음 조건을 만족해야 한다.

1. 에이전트가 Controller, UseCase, 도메인 규칙, Repository 계약, JPA Entity, Mapper, 조회 구현, 외부 Adapter의 위치를 판단할 수 있다.
2. 에이전트가 완전한 CQRS를 도입하지 않고 Command와 Query를 구분할 수 있다.
3. 상태 변경이 도메인 모델을 우회하지 못하도록 규칙이 명확하다.
4. Domain, Application, Presentation, Infrastructure의 의존 방향이 명확하다.
5. 다른 도메인과 Common 패키지의 경계가 명확하다.
6. 불필요한 패키지와 추상화를 생성하지 않는 기준이 있다.
7. 계층별 테스트 방법과 코드 리뷰 체크리스트를 만들 수 있다.
8. `AGENTS.md`는 일상적인 에이전트 작업에 충분히 짧고 상세 문서는 근거와 예시를 제공한다.
9. 특정 기능이나 인프라의 상세 설계를 포함하지 않는다.
