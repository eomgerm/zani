# DDD Development Guide for AI Agents

## 1. Document Contract

| Field | Value |
| --- | --- |
| Audience | AI coding agents and backend developers |
| Scope | `backend/src/main` and `backend/src/test` |
| Architecture | Single-module modular monolith |
| Root package | `com.a105.zani` |
| Status | Normative |

This guide defines where backend code belongs, which dependencies are allowed, and how agents should implement changes consistently. It describes architecture rules only; it does not prescribe business-specific features.

### Normative keywords

- **MUST / MUST NOT**: mandatory rule.
- **SHOULD / SHOULD NOT**: default rule; deviate only with a concrete reason.
- **MAY**: optional choice when the stated condition exists.

When a requested change appears to require violating a MUST rule, stop and ask the user before implementing it.

## 2. Core Invariants

These invariants take priority over examples elsewhere in this document.

| ID | Rule |
| --- | --- |
| ARCH-001 | Top-level application packages MUST represent business domains, not technical layers. |
| ARCH-002 | Dependency direction MUST be `presentation -> application -> domain`, with infrastructure implementing inward-facing contracts. |
| ARCH-003 | Domain code MUST remain pure Java and MUST NOT depend on Spring, JPA, HTTP, Feign, QueryDSL, or outer layers. |
| ARCH-004 | Every business state change that enforces an invariant or transition MUST pass through Aggregate behavior. Pure technical state MUST NOT require an artificial Domain Model. |
| ARCH-005 | Domain models and JPA entities MUST be separate types. |
| ARCH-006 | A domain's presentation, application, and domain layers MUST NOT access another domain's infrastructure. Infrastructure persistence entities MAY reference another domain's JPA entities for ERD foreign-key associations (S15P11A105-175). |
| ARCH-007 | State-changing and read-only UseCase code MUST be logically separated without assuming full CQRS infrastructure. |
| ARCH-008 | Packages and abstractions MUST NOT be created without a current use case. |
| ARCH-009 | `common` MUST contain shared technical contracts only, never business ownership. |
| ARCH-010 | `Repository` MUST mean an Aggregate persistence contract owned by Domain. Projection reads MUST use an Application-owned `QueryPort`. |
| ARCH-011 | Domain and Application errors MUST remain framework-free. HTTP mapping MUST occur at the outer web boundary. |
| ARCH-012 | Authentication, token, session, and JWT behavior MUST remain in the owning authentication domain, not `common`. |
| ARCH-013 | Application packages MUST represent business use cases, not technical `command`, `query`, or `service` categories. `Command` and `Query` names are reserved for meaningful UseCase input objects. |

## 3. Placement Decision Table

Use this table before creating a file.

| Code responsibility | Required location | Must depend on | Must not expose |
| --- | --- | --- | --- |
| REST controller | `<domain>.presentation.controller` | Application UseCase | Concrete service, JPA entity |
| HTTP request model | `<domain>.presentation.request` | Validation APIs only | Domain or persistence details |
| HTTP response model | `<domain>.presentation.response` | Application Result mapping | JPA entity |
| UseCase/input/result | `<domain>.application.<use-case>` | Domain contracts and Application ports | HTTP or persistence types |
| Application service for one UseCase | `<domain>.application.<use-case>` | Domain Repository, QueryPort, and Application ports | Infrastructure implementation |
| Application service shared by related UseCases | Narrowest existing business-capability package | Domain Repository, QueryPort, and Application ports | Unrelated UseCases or mixed transaction characteristics |
| Projection QueryPort | `<domain>.application.<read-use-case>` | Application Query/Result types | JPA, QueryDSL, or Infrastructure Row types |
| External-system Port | `<domain>.application.port` | Internal application/domain types | Vendor DTOs |
| Application error code | `<domain>.application.exception` | Framework-free common error contract | Spring, HTTP, or vendor exceptions |
| Aggregate/Value Object | `<domain>.domain.model` | Pure Java types | Framework annotations |
| Domain Repository contract | `<domain>.domain.repository` | Aggregate Root and Domain types | Projection Results or Spring Data types |
| Domain policy | `<domain>.domain.policy` | Domain models | Spring services |
| Domain error code | `<domain>.domain.exception` | Framework-free common error contract | HTTP status or Application errors |
| JPA entity | `<domain>.infrastructure.persistence.entity` | JPA APIs | Presentation or application |
| Persistence mapper | `<domain>.infrastructure.persistence.mapper` | Domain model and JPA entity | Mapping concerns outside infrastructure |
| Spring Data repository | `<domain>.infrastructure.persistence.repository` | JPA entity | Domain repository consumers |
| Persistence adapter | `<domain>.infrastructure.persistence` | Domain repository and mapper | Spring Data repository outside infrastructure |
| QueryPort adapter/optional Row | `<domain>.infrastructure.persistence.query` | Application QueryPort and query implementation APIs | QueryDSL annotations in application |
| External client/DTO/config | `<domain>.infrastructure.<system>` | Vendor/client APIs | Vendor contracts outside infrastructure |
| Shared technical code | `common` | Stable technical contracts | Domain-specific behavior |
| Shared error contracts | `common.error` | Java types only | Spring, HTTP, or domain ownership |
| Global MVC error handler | `common.error.handler` | Common error and response contracts, Spring Web | Handler types in Domain or Application |
| Security failure writers | `<auth-domain>.infrastructure.security` | Common error/response contracts, Spring Security | Authentication behavior in `common` |

If a responsibility is not listed, place it in the innermost layer that can own it without introducing an outward dependency.

## 4. Package Template

Create only the packages required by the current feature.

```text
com.a105.zani
├── ZaniBeApplication.java
├── common
└── <domain>
    ├── presentation
    │   ├── controller
    │   ├── request
    │   └── response
    ├── application
    │   ├── <state-changing-use-case>
    │   │   ├── <UseCase>Command.java
    │   │   ├── <UseCase>Result.java
    │   │   ├── <UseCase>UseCase.java
    │   │   └── <Responsibility>Service.java
    │   ├── <read-only-use-case>
    │   │   ├── <UseCase>Query.java
    │   │   ├── <UseCase>Result.java
    │   │   ├── <UseCase>UseCase.java
    │   │   ├── <UseCase>QueryPort.java
    │   │   └── <Responsibility>Service.java
    │   ├── port
    │   └── exception
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

Do not create:

- `domain.policy` when no cross-object domain rule exists.
- `infrastructure.persistence.query` for simple repository reads.
- `application.port` when no external dependency exists.
- `application.exception` when Application owns no orchestration or dependency failure.
- a QueryPort or query adapter when no projection-oriented read exists.
- an intermediate business-capability package until multiple current use cases require it.
- technical `application.command`, `application.query`, or `application.service` packages.
- empty Command, Query, or Result types.
- placeholder directories that contain no source file.

## 5. Dependency Rules

```text
presentation -> application -> domain
                    ^
             infrastructure
```

### Allowed dependencies

| From | May depend on |
| --- | --- |
| Domain | Java standard library, domain-owned types, and framework-free common error contracts |
| Application | Domain, application-owned contracts, and framework-free common error contracts |
| Presentation | Application UseCases and presentation-owned models |
| Infrastructure | Domain contracts, Application contracts and ports, infrastructure libraries |
| Common | Stable shared technical contracts |

### Forbidden dependencies

- Domain MUST NOT import Spring, Jakarta Persistence, Feign, QueryDSL, servlet, or presentation types.
- Application MUST NOT import JPA entities, Spring Data repositories, external client DTOs, or infrastructure adapters.
- Presentation MUST NOT call a concrete Application Service when a UseCase contract is the public boundary.
- Infrastructure types MUST NOT leak through Application or Domain method signatures.
- One domain's presentation, application, or domain layer MUST NOT import another domain's `infrastructure` package. Infrastructure-to-infrastructure JPA entity references for ERD foreign-key associations (for example `@ManyToOne`) are allowed (S15P11A105-175).
- Domain and Application error contracts MUST NOT import Spring Web, `HttpStatus`, `ResponseEntity`, or vendor exception types.
- Authentication behavior MUST NOT move into `common` merely because multiple endpoints use it.
- Application MUST NOT use global technical `command`, `query`, or `service` packages. Place each UseCase in a business-named package.

## 6. UseCase State Classification and Input Decision Procedure

Ask the following question first:

> Can this operation change persisted state, emit a state-changing event, or enforce a state transition?

- If **yes**, it is a state-changing UseCase and MAY accept a meaningful `*Command` input object.
- If **no**, it is a read-only UseCase and MAY accept a meaningful `*Query` input object.

This is logical separation inside one application and one database. Do not introduce a separate read database, event bus, or full CQRS stack unless the user explicitly requests it.

`Command` and `Query` name input objects, not Application packages, Services, or UseCase interfaces. Place the UseCase, its input, Result, Service, and optional QueryPort together in a business use-case package.

### 6.1 State-changing UseCase workflow

Business state change:

```text
HTTP Request
-> Command
-> UseCase
-> Application Service
-> Aggregate behavior
-> Domain Repository
-> Persistence Adapter
```

Pure technical state change:

```text
HTTP Request
-> Command
-> UseCase
-> Application Service
-> Application Port
-> Infrastructure Adapter
```

Rules:

- The Application UseCase boundary MUST own transaction demarcation.
- An Application Service public operation MUST use `@Transactional` when relational persistence work must be atomic.
- A state-changing UseCase that only uses Redis, files, or remote APIs SHOULD NOT receive a transaction mechanically.
- The service MUST load or create an Aggregate before changing business state.
- The service MUST invoke Aggregate behavior rather than mutating business fields directly.
- The Aggregate MUST reject invalid business transitions.
- Cache entries, token storage, file transfer state, and transport metadata MAY use an Application Port without an artificial Domain Model.
- Do not create a Command object when there is no meaningful input.
- Do not create a Result object when the operation has no meaningful output.

### 6.2 Read-only UseCase workflow

Aggregate or domain-decision read:

```text
HTTP Request
-> Query
-> UseCase
-> Application Service
-> Domain Repository
-> Domain Model
-> Application Result
```

Projection, list, search, aggregation, report, or cross-Aggregate read:

```text
HTTP Request
-> Query
-> UseCase
-> Application Service
-> Application QueryPort
-> Infrastructure Query Adapter and optional Row
-> Application Result
```

Rules:

- A read-only UseCase MUST NOT change state.
- Do not create a Query object when there is no meaningful input.
- Do not create a Result object when the operation has no meaningful output.
- Use `@Transactional(readOnly = true)` only when a persistence context or consistent relational read is useful.
- Select Domain Repository or QueryPort by return purpose, not SQL complexity.
- Use a Domain Repository when the UseCase needs an Aggregate or domain behavior.
- Use a QueryPort for a projection, page, search, aggregation, report, or cross-Aggregate read.
- A QueryPort MUST be owned by Application and implemented by Infrastructure.
- A QueryPort MUST NOT expose JPA, QueryDSL, or Infrastructure Row types.
- Prefer Spring Data JPA or JPQL for a simple implementation.
- Use QueryDSL only when the implementation needs dynamic conditions, complex joins, or aggregation.
- Do not load complete Aggregates solely to build a report.
- Application Result types MUST NOT use `@QueryProjection`.
- JPQL constructor expressions SHOULD target an Infrastructure Row, not an Application Result.

## 7. UseCase and Service Granularity

### UseCase

- A UseCase MUST be a narrow Application interface.
- A UseCase MUST expose exactly one public application operation.
- Its input, output, Service, and optional QueryPort MUST live in the same business use-case package unless a shared Service belongs to an existing common capability.

```java
public interface CreateResourceUseCase {
    CreateResourceResult create(CreateResourceCommand command);
}
```

### Application Service

- A Service implementing one UseCase MUST live in that use-case package.
- Related UseCases MAY share one Application Service only when they share the same Aggregate, transaction characteristics, and primary dependencies.
- A shared Application Service MUST live in the narrowest existing business-capability package common to those UseCases.
- A Service MUST NOT combine state-changing and read-only UseCases because their transaction characteristics differ.
- Do not force a one-to-one UseCase-to-Service mapping.
- Split an Application Service when its Aggregate, transaction characteristics, or primary dependencies diverge.
- Prefer a responsibility name over an `Impl` suffix.

```java
@Service
@RequiredArgsConstructor
class ResourceLifecycleService
        implements CreateResourceUseCase,
                   StartResourceUseCase,
                   CloseResourceUseCase {
}
```

## 8. Domain Modeling Rules

- An Aggregate MUST protect its own invariants.
- State-changing setters SHOULD NOT be public.
- Use behavior names such as `start()`, `close()`, or `changeOwner(...)`.
- Value Objects SHOULD validate their own construction rules.
- Domain errors SHOULD describe business meaning without HTTP status codes.

### Domain Policy

Use a Policy only when a pure business rule spans multiple Domain Models and does not naturally belong to one of them.

- In this guide, `Service` means an Application Service that implements UseCases.
- Prefer behavior on a Domain Model for single-object rules.
- Do not create a default `domain.service` package.
- A Policy MUST be pure Java and MUST NOT use Spring's `@Service`.

## 9. Persistence Boundary

Required separation:

```text
domain/model/Resource.java
infrastructure/persistence/entity/ResourceJpaEntity.java
infrastructure/persistence/mapper/ResourcePersistenceMapper.java
```

Write path:

```text
Application Service
-> Domain Model
-> Domain Repository
-> Persistence Adapter
-> Persistence Mapper
-> JPA Entity
-> Spring Data JPA Repository
```

Rules:

- Domain Models MUST NOT use `@Entity`, `@Table`, or `@Column`.
- JPA relationships and lazy loading MUST remain Infrastructure concerns.
- Mappers MUST make Domain Model/JPA Entity conversion explicit.
- Domain Repository interfaces MUST use Domain types.
- Persistence Adapters MUST hide Spring Data repositories.
- Shared audit fields MAY live in `common.infrastructure.persistence.BaseJpaEntity`.

## 10. QueryPort Naming and Ownership

DDD Repository and projection query contracts are different concepts.

```text
domain/repository/ResourceRepository.java
application/listresources/GetResourceSummaryQueryPort.java
infrastructure/persistence/query/ResourceSummaryQueryAdapter.java
infrastructure/persistence/query/ResourceSummaryRow.java
```

Rules:

- The name `Repository` MUST be reserved for an Aggregate persistence contract in Domain.
- A projection query contract MUST use a `QueryPort` name and live with its read-only UseCase in Application.
- QueryPort implementations and any persistence Rows MUST remain in Infrastructure.
- SQL simplicity MUST NOT determine ownership: an Aggregate need uses a Domain Repository; a projection need uses a QueryPort.

## 11. External Systems: Port and Adapter

```text
application/port/ExternalResourcePort.java
infrastructure/<system>/adapter/ExternalResourceAdapter.java
infrastructure/<system>/client/ExternalResourceClient.java
```

The Adapter MUST:

- convert internal input into the vendor request;
- call the external client;
- convert the vendor response into an internal type;
- map vendor failures to an Application-owned ErrorCode carried by the common `BusinessException`;
- allow Application to distinguish dependency failure, unavailability, and timeout when the UseCase requires it;
- prevent vendor DTOs and client exceptions from leaking inward.

Do not create a Port or Adapter until an external dependency exists.

## 12. Cross-Domain Collaboration

Forbidden:

```text
domainB.application
-> domainA.infrastructure.persistence
```

Preferred synchronous collaboration:

```text
domainB.application
-> domainA.application.getsummary.GetSummaryUseCase
```

Rules:

- Call another domain directly through an Application UseCase it intentionally exposes when an immediate result is required.
- Do not use another domain's repository implementation.
- Do not mutate another domain's Domain Model.
- If direct Application dependencies become cyclic, redesign the flow and consider a caller-owned Port or an event for the concrete need.
- Consider events only when asynchronous processing or reduced coupling provides concrete value.
- Do not add a Port or event to every cross-domain call by default.

## 13. Common Package Policy

`common` is a shared technical area, not a business domain.

Allowed examples:

- framework configuration shared by multiple domains;
- global response and error contracts;
- global exception handling;
- stable technical abstractions;
- framework-free `ErrorCode`, `ErrorType`, and `BusinessException` contracts;
- shared security configuration or current-user contracts with no authentication use-case ownership;
- shared persistence auditing base types.

Forbidden examples:

- domain-specific Request or Response types;
- Domain Repositories or business Services;
- clients used by only one domain;
- token issuance or validation, JWT implementation, refresh sessions, and login rules owned by the authentication domain;
- business models moved only because two callers use them;
- an unbounded `util` package with unclear ownership.

## 14. Exception Ownership and Outer Handling

Domain and Application MAY depend on minimal common error contracts only when those contracts remain independent of Spring and HTTP.

```text
common.error.ErrorCode
common.error.ErrorType
common.error.BusinessException

<domain>.domain.exception.<Domain>ErrorCode
<domain>.application.exception.<UseCase>ErrorCode

common.error.handler.ErrorTypeHttpStatusMapper
common.error.handler.GlobalExceptionHandler
<auth-domain>.infrastructure.security.AuthenticationEntryPoint
<auth-domain>.infrastructure.security.AccessDeniedHandler
```

### Ownership

- Domain ErrorCodes MUST describe business invariant violations and invalid state transitions.
- Application ErrorCodes MUST describe use-case orchestration and external dependency failures.
- Infrastructure MUST convert vendor exceptions to an Application-owned ErrorCode and the common `BusinessException`.
- `common` MUST own only the framework-free ErrorCode, ErrorType, and BusinessException contracts.
- Domain-specific or error-specific RuntimeException classes SHOULD NOT be created when the common BusinessException is sufficient.

### Web boundary

- ErrorCode and BusinessException MUST NOT contain `HttpStatus`, `ResponseEntity`, or another HTTP type.
- One shared `GlobalExceptionHandler` MUST handle MVC-visible BusinessExceptions.
- An outer `ErrorTypeHttpStatusMapper` MUST map ErrorType to `HttpStatus`.
- Spring Security authentication and authorization failures occur before MVC exception advice; `AuthenticationEntryPoint` and `AccessDeniedHandler` MUST handle them.
- Security failure handlers MUST use the same common ErrorCode and response writer as the MVC error boundary.
- Domain and Application MUST NOT depend on GlobalExceptionHandler, the HTTP mapper, or security handlers.

API payload fields, validation-detail shape, logging levels, request metadata, and tracing policy are outside this DDD guide.

## 15. Naming Rules

| Kind | Preferred pattern | Avoid |
| --- | --- | --- |
| Command input | `CreateResourceCommand` | `CreateResourceRequest` in Application |
| Query input | `GetResourceQuery`, `SearchResourcesQuery` | `ResourceSearchRequest` in Application |
| State-changing UseCase | `CreateResourceUseCase` | Generic `ResourceUseCase` |
| Read-only UseCase | `GetResourceUseCase`, `GetResourceListUseCase` | Ambiguous `FindService` |
| Application Service | `ResourceLifecycleService` | `ResourceServiceImpl` |
| Domain Repository | `ResourceRepository` | Spring-specific name |
| Projection QueryPort | `GetResourceSummaryQueryPort` | `ResourceQueryRepository` |
| Persistence Adapter | `ResourcePersistenceAdapter` | Exposing Spring Data repository |
| JPA Entity | `ResourceJpaEntity` | Reusing Domain Model |
| Persistence Mapper | `ResourcePersistenceMapper` | Generic global mapper |
| Query Row | `ResourceSummaryRow` | Application Result with QueryDSL annotations |
| Application Result | `GetResourceResult`, `ResourceSummaryResult` | UI-specific `View` |
| External Adapter | `ExternalResourceAdapter` | Vendor DTO returned inward |
| Domain ErrorCode | `ResourceErrorCode` | HTTP-specific error name |
| Application ErrorCode | `ExternalResourceErrorCode` | Vendor exception name |

Names MUST express business responsibility. Avoid `Manager`, `Helper`, or `Util` unless the responsibility is both technical and precisely bounded.

## 16. Testing Matrix

| Layer | Test style | Real dependencies | Replace |
| --- | --- | --- | --- |
| Domain | Pure unit test | Domain Models and Policies | Nothing |
| Application | Unit test | UseCase service and domain objects | Domain Repository, QueryPort, and external Port with Mock/Fake |
| Presentation | MVC slice test | Controller, validation, mapping | Application UseCase |
| Persistence | JPA slice test | Entity mapping, mapper, adapter, query | External systems |
| External Adapter | Contract or mock-server test when valuable | Adapter and serialization | Remote system |
| Architecture | ArchUnit when boundaries exist | Package graph | N/A |

Tests MUST mirror the production domain package structure.

## 17. Prohibited Patterns

| ID | Do not |
| --- | --- |
| BAD-001 | Organize top-level code as global `controller`, `service`, and `repository` packages. |
| BAD-002 | Put business code into a generic `common` or `util` package. |
| BAD-003 | Mix JPA or HTTP responsibilities into a Domain Model. |
| BAD-004 | Reference another domain's entity or repository implementation. |
| BAD-005 | Put every UseCase into one God Service. |
| BAD-006 | Create one Service per UseCase mechanically. |
| BAD-007 | Use QueryDSL for every read. |
| BAD-008 | Load multiple complete Aggregates for a reporting query. |
| BAD-009 | Add QueryDSL annotations to Application Result types. |
| BAD-010 | Leak vendor DTOs into Application or Domain. |
| BAD-011 | Create empty packages, placeholder interfaces, or speculative abstractions. |
| BAD-012 | Call a projection QueryPort a Repository or place it in `domain.repository`. |
| BAD-013 | Put Spring or HTTP types into Domain or Application ErrorCode contracts. |
| BAD-014 | Create one RuntimeException class per domain or error when BusinessException is sufficient. |
| BAD-015 | Move authentication, token, session, or JWT behavior into `common`. |
| BAD-016 | Organize Application code into global technical `command`, `query`, or `service` packages. |
| BAD-017 | Use `Command` or `Query` as the name of a Service, UseCase interface, or package instead of an input object. |

## 18. Agent Workflow Checklist

### Before implementation

- [ ] Identify the owning business domain.
- [ ] Classify the UseCase as state-changing or read-only.
- [ ] Name the business use-case package without using technical `command`, `query`, or `service` categories.
- [ ] Use the placement table to map every new type.
- [ ] Inspect existing conventions in the same domain.
- [ ] Confirm that every proposed abstraction has a current caller.

### During implementation

- [ ] Preserve the dependency rules in Section 5.
- [ ] Route business state changes through Aggregate behavior.
- [ ] Do not create a Domain Model for pure technical state.
- [ ] Keep framework and vendor types in Infrastructure.
- [ ] Keep HTTP models in Presentation.
- [ ] Add tests at the layer where behavior changes.

### Before completion

- [ ] No inner layer imports an outer layer.
- [ ] No domain imports another domain's Infrastructure.
- [ ] State-changing and read-only UseCase responsibilities are not mixed.
- [ ] Command and Query names are used only for meaningful UseCase input objects.
- [ ] Application packages represent business use cases, with no global technical `command`, `query`, or `service` package.
- [ ] Aggregate reads use Domain Repository and projection reads use QueryPort.
- [ ] JPA entities do not escape Infrastructure.
- [ ] Domain Models contain no framework annotations.
- [ ] QueryPorts do not introduce unnecessary QueryDSL or Row types for simple implementations.
- [ ] UseCases expose exactly one public operation.
- [ ] Application Services group only UseCases that share Aggregate, transaction characteristics, and primary dependencies.
- [ ] Domain and Application errors contain no Spring, HTTP, or vendor types.
- [ ] Authentication ownership has not moved into `common`.
- [ ] No speculative package or abstraction was added.
- [ ] Relevant tests pass and the exact verification command is reported.
