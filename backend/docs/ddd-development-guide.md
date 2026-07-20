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
| ARCH-004 | Every state change MUST pass through domain behavior that protects invariants. |
| ARCH-005 | Domain models and JPA entities MUST be separate types. |
| ARCH-006 | A domain MUST NOT directly access another domain's JPA entities or infrastructure repositories. |
| ARCH-007 | Command and Query code MUST be logically separated without assuming full CQRS infrastructure. |
| ARCH-008 | Packages and abstractions MUST NOT be created without a current use case. |
| ARCH-009 | `common` MUST contain shared technical contracts only, never business ownership. |

## 3. Placement Decision Table

Use this table before creating a file.

| Code responsibility | Required location | Must depend on | Must not expose |
| --- | --- | --- | --- |
| REST controller | `<domain>.presentation.controller` | Application UseCase | Concrete service, JPA entity |
| HTTP request model | `<domain>.presentation.request` | Validation APIs only | Domain or persistence details |
| HTTP response model | `<domain>.presentation.response` | Application Result mapping | JPA entity |
| Command UseCase/input/result | `<domain>.application.command.<use-case>` | Domain contracts | HTTP or persistence types |
| Query UseCase/input/result | `<domain>.application.query.<use-case>` | Query contract or domain contract | QueryDSL types |
| Application service | `<domain>.application.command` or `<domain>.application.query` | Domain repository and application ports | Infrastructure implementation |
| External-system Port | `<domain>.application.port` | Internal application/domain types | Vendor DTOs |
| Aggregate/Value Object | `<domain>.domain.model` | Pure Java types | Framework annotations |
| Domain repository contract | `<domain>.domain.repository` | Domain model | Spring Data types |
| Domain policy | `<domain>.domain.policy` | Domain models | Spring services |
| Domain error | `<domain>.domain.exception` | Domain error contract | HTTP status |
| JPA entity | `<domain>.infrastructure.persistence.entity` | JPA APIs | Presentation or application |
| Persistence mapper | `<domain>.infrastructure.persistence.mapper` | Domain model and JPA entity | Mapping concerns outside infrastructure |
| Spring Data repository | `<domain>.infrastructure.persistence.repository` | JPA entity | Domain repository consumers |
| Persistence adapter | `<domain>.infrastructure.persistence` | Domain repository and mapper | Spring Data repository outside infrastructure |
| Complex query Row/adapter | `<domain>.infrastructure.persistence.query` | Query implementation APIs | QueryDSL annotations in application |
| External client/DTO/config | `<domain>.infrastructure.<system>` | Vendor/client APIs | Vendor contracts outside infrastructure |
| Shared technical code | `common` | Stable technical contracts | Domain-specific behavior |

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
    │   ├── command
    │   │   └── <use-case>
    │   ├── query
    │   │   └── <use-case>
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

Do not create:

- `domain.policy` when no cross-object domain rule exists.
- `infrastructure.persistence.query` for simple repository reads.
- `application.port` when no external dependency exists.
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
| Domain | Java standard library and domain-owned types |
| Application | Domain and application-owned contracts |
| Presentation | Application UseCases and presentation-owned models |
| Infrastructure | Domain contracts, Application contracts and ports, infrastructure libraries |
| Common | Stable shared technical contracts |

### Forbidden dependencies

- Domain MUST NOT import Spring, Jakarta Persistence, Feign, QueryDSL, servlet, or presentation types.
- Application MUST NOT import JPA entities, Spring Data repositories, external client DTOs, or infrastructure adapters.
- Presentation MUST NOT call a concrete Application Service when a UseCase contract is the public boundary.
- Infrastructure types MUST NOT leak through Application or Domain method signatures.
- One domain MUST NOT import another domain's `infrastructure` package.

## 6. Command and Query Decision Procedure

Ask the following question first:

> Can this operation change persisted state, emit a state-changing event, or enforce a state transition?

- If **yes**, implement it as a Command.
- If **no**, implement it as a Query.

This is logical separation inside one application and one database. Do not introduce a separate read database, event bus, or full CQRS stack unless the user explicitly requests it.

### 6.1 Command workflow

```text
HTTP Request
-> Command
-> Command UseCase
-> Application Service
-> Domain Model behavior
-> Domain Repository
-> Persistence Adapter
```

Rules:

- The Application boundary MUST own `@Transactional`.
- The service MUST load or create a Domain Model before changing state.
- The service MUST invoke domain behavior rather than mutating fields directly.
- The Domain Model MUST reject invalid transitions.
- Do not create a Command object when there is no meaningful input.
- Do not create a Result object when the operation has no meaningful output.

### 6.2 Query workflow

Simple aggregate read:

```text
Persistence
-> Domain Model
-> Application Result
```

Complex list, search, aggregation, or report:

```text
JPQL or QueryDSL
-> Infrastructure Query Row
-> Query Repository Adapter
-> Application Result
```

Rules:

- A Query MUST NOT change state.
- Use `@Transactional(readOnly = true)` when a transaction is useful.
- Prefer Spring Data JPA or JPQL for simple reads.
- Use QueryDSL only for dynamic conditions, complex joins, or aggregation.
- Do not load complete Aggregates solely to build a report.
- Application Result types MUST NOT use `@QueryProjection`.
- JPQL constructor expressions SHOULD target an Infrastructure Row, not an Application Result.

## 7. UseCase and Service Granularity

### UseCase

- A UseCase MUST be a narrow Application interface.
- A UseCase SHOULD normally expose one operation.
- Its input and output types SHOULD live with that use case.

```java
public interface CreateResourceUseCase {
    CreateResourceResult create(CreateResourceCommand command);
}
```

### Application Service

- Related UseCases MAY share one service when they operate on the same Aggregate and share dependencies.
- Do not force a one-to-one UseCase-to-Service mapping.
- Split a service when responsibilities or dependencies become unrelated.
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

- Prefer behavior on a Domain Model for single-object rules.
- Do not create a default `domain.service` package.
- A Policy SHOULD be pure Java and SHOULD NOT use Spring's `@Service`.

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

## 10. External Systems: Port and Adapter

```text
application/port/ExternalResourcePort.java
infrastructure/<system>/adapter/ExternalResourceAdapter.java
infrastructure/<system>/client/ExternalResourceClient.java
```

The Adapter MUST:

- convert internal input into the vendor request;
- call the external client;
- convert the vendor response into an internal type;
- map external failures to application-level errors;
- prevent vendor DTOs and client exceptions from leaking inward.

Do not create a Port or Adapter until an external dependency exists.

## 11. Cross-Domain Collaboration

Forbidden:

```text
domainB.application
-> domainA.infrastructure.persistence
```

Preferred synchronous collaboration:

```text
domainB.application
-> domainA.application.query.GetSummaryUseCase
```

Rules:

- Call another domain through a UseCase it intentionally exposes.
- Do not use another domain's repository implementation.
- Do not mutate another domain's Domain Model.
- Consider events only when asynchronous processing or reduced coupling provides concrete value.
- Do not make every cross-domain call event-driven by default.

## 12. Common Package Policy

`common` is a shared technical area, not a business domain.

Allowed examples:

- framework configuration shared by multiple domains;
- global response and error contracts;
- global exception handling;
- stable technical abstractions;
- shared persistence auditing base types.

Forbidden examples:

- domain-specific Request or Response types;
- Domain Repositories or business Services;
- clients used by only one domain;
- business models moved only because two callers use them;
- an unbounded `util` package with unclear ownership.

## 13. Naming Rules

| Kind | Preferred pattern | Avoid |
| --- | --- | --- |
| Command UseCase | `CreateResourceUseCase` | Generic `ResourceUseCase` |
| Query UseCase | `GetResourceUseCase`, `GetResourceListUseCase` | Ambiguous `FindService` |
| Application Service | `ResourceLifecycleService` | `ResourceServiceImpl` |
| Domain Repository | `ResourceRepository` | Spring-specific name |
| Persistence Adapter | `ResourcePersistenceAdapter` | Exposing Spring Data repository |
| JPA Entity | `ResourceJpaEntity` | Reusing Domain Model |
| Persistence Mapper | `ResourcePersistenceMapper` | Generic global mapper |
| Query Row | `ResourceSummaryRow` | Application Result with QueryDSL annotations |
| Application Result | `GetResourceResult`, `ResourceSummaryResult` | UI-specific `View` |
| External Adapter | `ExternalResourceAdapter` | Vendor DTO returned inward |

Names MUST express business responsibility. Avoid `Manager`, `Helper`, or `Util` unless the responsibility is both technical and precisely bounded.

## 14. Testing Matrix

| Layer | Test style | Real dependencies | Replace |
| --- | --- | --- | --- |
| Domain | Pure unit test | Domain Models and Policies | Nothing |
| Application | Unit test | UseCase service and domain objects | Repository and Port with Mock/Fake |
| Presentation | MVC slice test | Controller, validation, mapping | Application UseCase |
| Persistence | JPA slice test | Entity mapping, mapper, adapter, query | External systems |
| External Adapter | Contract or mock-server test when valuable | Adapter and serialization | Remote system |
| Architecture | ArchUnit when boundaries exist | Package graph | N/A |

Tests MUST mirror the production domain package structure.

## 15. Prohibited Patterns

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

## 16. Agent Workflow Checklist

### Before implementation

- [ ] Identify the owning business domain.
- [ ] Classify the operation as Command or Query.
- [ ] Use the placement table to map every new type.
- [ ] Inspect existing conventions in the same domain.
- [ ] Confirm that every proposed abstraction has a current caller.

### During implementation

- [ ] Preserve the dependency rules in Section 5.
- [ ] Route state changes through Domain Model behavior.
- [ ] Keep framework and vendor types in Infrastructure.
- [ ] Keep HTTP models in Presentation.
- [ ] Add tests at the layer where behavior changes.

### Before completion

- [ ] No inner layer imports an outer layer.
- [ ] No domain imports another domain's Infrastructure.
- [ ] Command and Query responsibilities are not mixed.
- [ ] JPA entities do not escape Infrastructure.
- [ ] Domain Models contain no framework annotations.
- [ ] Simple reads do not use unnecessary query infrastructure.
- [ ] No speculative package or abstraction was added.
- [ ] Relevant tests pass and the exact verification command is reported.
