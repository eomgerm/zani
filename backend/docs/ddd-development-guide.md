# ZANI 백엔드 DDD 개발 가이드

## 1. 목적

ZANI 백엔드는 단일 Spring Boot 모듈을 모듈러 모놀리스로 운영한다. 기술 계층보다 업무 도메인 경계를 먼저 드러내고, 도메인 규칙을 프레임워크와 영속성 세부사항으로부터 보호한다.

이 가이드는 엄격한 헥사고날 아키텍처를 기계적으로 적용하기 위한 문서가 아니다. 일반적인 수평형 `controller-service-repository` 구조보다 도메인 경계를 강화하되, 실제 요구사항이 없는 추상화는 만들지 않는 실용적 DDD를 목표로 한다.

## 2. 최상위 패키지

```text
src/main/java/com/a105/zani
├── ZaniBeApplication.java
├── common
├── <domain-a>
├── <domain-b>
└── <domain-c>
```

- 최상위 패키지는 업무 도메인 또는 Bounded Context를 표현한다.
- `common`은 비즈니스 도메인이 아니다.
- 최상위에 `controller`, `service`, `repository`를 두지 않는다.
- 실제 도메인 경계는 요구사항을 분석한 뒤 정한다.

## 3. 도메인 내부 구조

```text
<domain>/
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
    └── <external-system>
        ├── client
        ├── config
        └── adapter
```

모든 도메인이 이 패키지를 전부 가질 필요는 없다.

- Policy가 없다면 `domain.policy`를 만들지 않는다.
- 복잡한 조회가 없다면 `infrastructure.persistence.query`를 만들지 않는다.
- 외부 연동이 없다면 `application.port`와 외부 Adapter를 만들지 않는다.
- 빈 패키지를 구조를 맞추기 위해 만들지 않는다.

## 4. 계층별 책임

### 4.1 Presentation

- REST Controller
- HTTP 요청 검증
- API Request를 Command 또는 Query로 변환
- Application Result를 API Response로 변환
- HTTP에만 필요한 상태 코드와 헤더 처리

Presentation은 Application UseCase 인터페이스에 의존한다. 구체 Service 구현체를 직접 호출하지 않는다.

### 4.2 Application

- 유스케이스 흐름 조정
- 트랜잭션 경계
- 도메인 모델과 Repository 계약 호출
- 외부 Port 호출
- Command와 Query 구분

Application은 비즈니스 규칙을 직접 품기보다 도메인 객체가 규칙을 실행하도록 협력 순서를 구성한다.

### 4.3 Domain

- Aggregate, Entity, Value Object
- 비즈니스 불변식과 상태 전이
- Domain Repository 인터페이스
- 여러 도메인 객체에 걸친 순수 Policy
- 도메인 예외

Domain은 순수 Java로 작성한다. Spring, JPA, HTTP, Feign, QueryDSL을 알지 못한다.

### 4.4 Infrastructure

- JPA Entity와 Spring Data JPA Repository
- Domain Repository 구현 Adapter
- Persistence Mapper
- JPQL과 QueryDSL 조회 구현
- 외부 시스템 Client와 Adapter
- 프레임워크 및 기술 설정

## 5. 의존 방향

```text
presentation -> application -> domain
                    ^
             infrastructure
```

- Domain은 다른 계층에 의존하지 않는다.
- Application은 Domain에 의존한다.
- Presentation은 Application UseCase에 의존한다.
- Infrastructure는 Domain Repository 또는 Application Port를 구현한다.
- Application과 Domain은 Infrastructure 구현체를 직접 참조하지 않는다.
- 다른 도메인의 Infrastructure와 JPA Entity를 직접 사용하지 않는다.

## 6. Command와 Query

Command와 Query는 별도 서버나 별도 데이터베이스를 사용하는 CQRS를 의미하지 않는다. 하나의 애플리케이션 안에서 요청 목적과 코드 경계를 구분한다.

### 6.1 Command

```text
API Request
-> Command
-> UseCase
-> Application Service
-> Domain Model
-> Domain Repository
-> Persistence Adapter
```

- Application 경계에 `@Transactional`을 적용한다.
- 상태 변경은 반드시 도메인 모델 메서드를 통과한다.
- 의미 있는 입력이 없으면 Command 객체를 만들지 않는다.
- 반환값이 없으면 Result를 만들지 않는다.

### 6.2 Query

단순 조회:

```text
JPA Entity
-> Domain Model
-> Application Result
```

목록·검색·집계·리포트:

```text
JPQL 또는 QueryDSL
-> Infrastructure Query Row
-> Query Repository Adapter
-> Application Result
```

- 필요한 경우 `@Transactional(readOnly = true)`를 사용한다.
- 단순 조회는 Spring Data JPA 또는 JPQL로 처리한다.
- 동적 조건, 복잡한 조인, 집계가 있을 때 QueryDSL을 선택한다.
- 복잡한 조회 때문에 여러 Aggregate 전체를 메모리에 올리지 않는다.
- Application Result에 `@QueryProjection`을 붙이지 않는다.
- JPQL `select new`는 Infrastructure Row를 대상으로 한다.

## 7. UseCase와 Application Service

UseCase는 Application이 외부에 제공하는 좁은 인터페이스다. 일반적으로 하나의 동작을 제공한다.

```java
public interface CreateResourceUseCase {

    CreateResourceResult create(CreateResourceCommand command);
}
```

관련 UseCase가 같은 Aggregate와 의존성을 공유하면 하나의 Service가 구현할 수 있다.

```java
@Service
@RequiredArgsConstructor
class ResourceLifecycleService
        implements CreateResourceUseCase,
                   StartResourceUseCase,
                   CloseResourceUseCase {
}
```

- UseCase와 Service를 1:1로 강제하지 않는다.
- 모든 기능을 하나의 God Service에 넣지 않는다.
- 모든 UseCase마다 Service를 따로 만드는 것도 피한다.
- `ResourceServiceImpl`보다 책임을 나타내는 이름을 사용한다.

## 8. Domain Model과 JPA Entity

```text
domain/model/Resource.java
infrastructure/persistence/entity/ResourceJpaEntity.java
infrastructure/persistence/mapper/ResourcePersistenceMapper.java
```

```text
Application Service
-> Domain Model
-> Domain Repository
-> Persistence Adapter
-> Persistence Mapper
-> JPA Entity
-> Spring Data JPA Repository
```

- Domain Model에 `@Entity`, `@Table`, `@Column`을 붙이지 않는다.
- JPA 연관관계와 지연 로딩은 Infrastructure 내부 문제로 제한한다.
- Mapper가 Domain Model과 JPA Entity를 명시적으로 변환한다.
- Domain Repository 인터페이스는 Domain에 둔다.
- Persistence Adapter가 Domain Repository를 구현한다.

## 9. Domain Policy

단일 객체가 처리할 수 있는 규칙은 해당 Domain Model에 둔다.

```java
resource.start();
resource.close();
resource.changeOwner(ownerId);
```

여러 도메인 객체에 걸친 순수 규칙이 필요할 때만 Policy를 사용한다.

- 기본 구조에서는 `domain.service`를 만들지 않는다.
- Policy에는 일반적으로 Spring `@Service`를 붙이지 않는다.

## 10. Port와 Adapter

Port는 Application이 외부 시스템에 요구하는 기능의 계약이다. Adapter는 Port를 특정 기술로 구현한다.

```text
application/port/ExternalResourcePort.java
infrastructure/<external-system>/adapter/ExternalResourceAdapter.java
infrastructure/<external-system>/client/ExternalResourceClient.java
```

Adapter는 Application 입력을 외부 Request로 변환하고, 외부 Response를 내부 객체로 변환하며, 외부 오류를 애플리케이션 예외로 바꾼다. 외부 시스템의 상세 계약이 Application이나 Domain으로 유출되지 않게 한다.

## 11. 도메인 간 호출

잘못된 접근:

```text
domainB/application
-> domainA/infrastructure/persistence/entity
```

권장 접근:

```text
domainB/application
-> domainA/application/query/GetSummaryUseCase
```

- 동기 호출은 상대 도메인이 공개한 UseCase를 사용한다.
- 다른 도메인의 Repository 구현체를 직접 사용하지 않는다.
- 다른 도메인의 Domain Model을 직접 수정하지 않는다.
- 비동기 처리나 결합도 완화가 실제로 필요할 때 이벤트를 검토한다.
- 모든 도메인 호출을 처음부터 이벤트로 만들지 않는다.

## 12. Common 패키지

```text
common/
├── config
├── response
├── error
└── infrastructure
    └── persistence
```

둘 수 있는 코드:

- 여러 도메인이 공유하는 프레임워크 설정
- 전역 예외 처리
- 공통 API 응답 계약
- 안정적인 공통 오류 분류
- JPA Auditing Base Entity

두지 않는 코드:

- 특정 도메인의 Request와 Response
- Domain Repository
- 비즈니스 Service
- 특정 도메인에서만 사용하는 외부 Client
- 단지 두 곳에서 사용된다는 이유로 옮긴 Domain Model

## 13. 테스트

```text
src/test/java/com/a105/zani
├── architecture
├── <domain-a>
│   ├── presentation
│   ├── application
│   ├── domain
│   └── infrastructure
└── <domain-b>
```

- Domain: Spring 없는 순수 단위 테스트
- Application: Repository와 Port를 Mock 또는 Fake로 대체
- Presentation: MVC Slice Test
- Persistence: JPA Slice Test
- 외부 Adapter: 필요할 때 Mock Server 또는 계약 테스트
- Architecture: 실제 패키지 경계가 생긴 뒤 가치가 있을 때 ArchUnit 검토

## 14. 피해야 할 구조

- 최상위 `controller`, `service`, `repository` 중심의 수평 패키지
- 모든 코드를 모으는 거대한 `common` 또는 `util`
- Domain Model에 JPA와 HTTP 책임 혼합
- 다른 도메인의 Repository와 Entity 직접 참조
- 모든 UseCase를 하나의 Service에 몰아넣는 God Service
- 모든 UseCase마다 Service를 하나씩 만드는 과도한 보일러플레이트
- 단순 조회까지 무조건 QueryDSL 사용
- 복잡한 집계를 위해 여러 Aggregate 전체 로딩
- Application Result의 QueryDSL 의존
- 외부 시스템 DTO가 Application 또는 Domain으로 유출
- 실제 기능이 없는 빈 패키지와 인터페이스

## 15. 코드 리뷰 체크리스트

- [ ] 최상위 패키지가 도메인 기준으로 나뉘어 있는가?
- [ ] Domain이 Spring, JPA, HTTP, Feign을 모르는가?
- [ ] Controller가 Application UseCase에 의존하는가?
- [ ] 상태 변경이 Domain Model 메서드를 통과하는가?
- [ ] Command와 Query의 목적이 구분되는가?
- [ ] 단순 조회에 불필요한 QueryDSL을 사용하지 않았는가?
- [ ] JPA Entity와 Domain Model의 변환 경계가 명확한가?
- [ ] 외부 시스템이 Port와 Adapter 뒤에 숨겨져 있는가?
- [ ] 다른 도메인의 Infrastructure를 직접 참조하지 않는가?
- [ ] Common에 비즈니스 코드가 쌓이지 않았는가?
- [ ] Service가 지나치게 크거나 기계적으로 분리되지 않았는가?
- [ ] 필요하지 않은 패키지와 추상화를 만들지 않았는가?
