# ZANI 백엔드 에이전트 지침

이 파일은 `backend` 디렉터리 전체에 적용된다. 코드를 추가하거나 수정하기 전에 [DDD 개발 가이드](docs/ddd-development-guide.md)를 읽고 아래 규칙을 따른다.

## 기본 방향

- Java 21, Spring Boot 4.1, Gradle 단일 모듈을 기준으로 작업한다.
- 최상위 패키지는 `controller`, `service`, `repository` 같은 기술 계층이 아니라 업무 도메인으로 나눈다.
- 현재 기능에 필요하지 않은 패키지, 인터페이스, DTO, 추상 계층을 미리 만들지 않는다.
- 새로운 패턴을 도입하기 전에 같은 도메인의 기존 코드를 확인하고 그 스타일을 우선한다.

## 도메인 내부 구조

필요한 책임만 다음 위치에 둔다.

```text
<domain>/
├── presentation
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
    └── <external-system>
```

- 빈 디렉터리를 구조 목적으로 만들지 않는다.
- HTTP Request/Response는 `presentation` 밖으로 유출하지 않는다.
- JPA Entity, Spring Data Repository, QueryDSL 타입, 외부 API DTO는 `infrastructure` 밖으로 유출하지 않는다.

## 의존 방향

```text
presentation -> application -> domain
                    ^
             infrastructure
```

- `domain`은 Spring, JPA, HTTP, Feign과 다른 계층을 알지 못한다.
- `application`은 `domain`에만 의존하며 유스케이스 흐름과 트랜잭션을 관리한다.
- `presentation`은 구체 Service가 아닌 Application UseCase에 의존한다.
- `infrastructure`는 Domain Repository 또는 Application Port를 구현한다.
- `application`과 `domain`에서 Infrastructure 구현체를 직접 참조하지 않는다.
- 다른 도메인의 JPA Entity나 Infrastructure Repository에 직접 접근하지 않는다.

## Command와 Query

- 생성, 수정, 삭제, 상태 전이는 Command다.
- 읽기 전용 요청은 Query다.
- 이는 코드 목적을 구분하는 경량 분리이며 별도 Read DB나 완전한 CQRS를 의미하지 않는다.
- Command는 Application 경계에서 `@Transactional`을 사용하고 도메인 모델 메서드로 상태를 변경한다.
- Query는 상태를 변경하지 않으며 필요한 경우 `@Transactional(readOnly = true)`를 사용한다.
- 단순 조회에는 Spring Data JPA 또는 JPQL을 사용할 수 있다.
- 동적 조건, 복잡한 조인, 집계가 있을 때만 전용 Query Repository나 QueryDSL을 고려한다.
- 입력이 없으면 Command/Query 객체를, 반환값이 없으면 Result 객체를 억지로 만들지 않는다.

## UseCase와 Service

- UseCase는 외부에 공개하는 좁은 Application 인터페이스이며 일반적으로 하나의 동작을 가진다.
- 같은 Aggregate와 의존성을 공유하는 관련 UseCase는 하나의 책임 있는 Service가 구현할 수 있다.
- UseCase와 Service를 기계적으로 1:1로 만들지 않는다.
- `Impl`보다 `ResourceLifecycleService`처럼 책임을 나타내는 이름을 사용한다.
- Controller가 Application Service 구현체를 직접 참조하지 않게 한다.

## 도메인과 영속성

- 모든 상태 변경은 도메인 모델 메서드를 통과한다.
- 도메인 모델에는 JPA 어노테이션을 붙이지 않는다.
- 도메인 모델과 JPA Entity를 별도 타입으로 둔다.
- Persistence Mapper가 두 타입을 명시적으로 변환한다.
- Domain Repository 인터페이스는 `domain`에 두고 Persistence Adapter가 구현한다.
- 복잡한 조회용 Row는 Infrastructure에 두고 Application Result로 변환한다.
- Application Result에 QueryDSL 어노테이션을 붙이지 않는다.

## Port와 Adapter

- Application이 외부 시스템에 요구하는 계약은 Port로 정의한다.
- 기술별 구현은 Adapter에 둔다.
- 외부 API Request/Response와 Client 예외가 Application 또는 Domain으로 유출되지 않게 한다.
- 외부 연동이 없다면 Port와 Adapter를 만들지 않는다.

## Common

- `common`에는 여러 도메인이 실제로 공유하는 기술 설정과 공통 계약만 둔다.
- 도메인 Request/Response, Repository, Service, 비즈니스 모델을 `common`으로 이동하지 않는다.
- 두 군데에서 사용된다는 이유만으로 공통화하지 않는다.
- 소유자가 불분명한 거대한 `util` 패키지를 만들지 않는다.

## 테스트

- Domain은 Spring 없는 순수 단위 테스트로 검증한다.
- Application은 Repository와 Port를 Mock 또는 Fake로 대체한다.
- Presentation은 MVC Slice Test를 사용한다.
- Persistence는 JPA Slice Test를 사용한다.
- 테스트 패키지는 운영 코드의 도메인 구조를 따른다.
- 경계가 실제로 생기고 자동 검증 가치가 있을 때만 ArchUnit을 도입한다.

## 작업 완료 전 확인

- 상태 변경이 도메인 모델을 우회하지 않는가?
- 의존 방향을 역행하지 않는가?
- 다른 도메인의 Infrastructure를 직접 참조하지 않는가?
- 단순 조회에 불필요한 QueryDSL을 사용하지 않았는가?
- 불필요한 패키지나 추상화를 추가하지 않았는가?
- 새 동작을 해당 계층 수준의 테스트로 검증했는가?
