# AI Agent Documentation Index

## Scope

This file applies to the entire `backend` directory.

All documentation intended to instruct AI agents MUST be written in English.

This file is both the entry-point index and the default home for concise agent instructions.

## Instruction Precedence

Apply instructions in this order:

1. The current user request.
2. A more specific `AGENTS.md` located closer to the file being changed.
3. This root `AGENTS.md`.
4. The guides listed in the document registry below.
5. Established conventions in the affected domain.

If two instructions conflict and precedence does not resolve the conflict, stop and ask the user.

## Required Reading

Before changing application code, package structure, persistence code, or architecture:

1. Read this index.
2. Read [DDD Development Guide](docs/ddd-development-guide.md).
3. Inspect the existing files in the affected business domain.

Do not infer a project-wide convention from a single file when the guide defines a different rule.

## Repository Context

- Language: Java 21
- Framework: Spring Boot 4.1
- Build: Gradle
- Architecture: single-module modular monolith
- Root package: `com.a105.zani`

## Agent Operating Protocol

### Before editing

- Identify the business domain affected by the request.
- Classify each operation as a Command or Query.
- Determine which layers need to change.
- Search for existing patterns in the same domain.
- Avoid creating packages or abstractions for hypothetical future work.

### While editing

- Follow the DDD guide as the single source of truth for architecture.
- Keep changes within the requested scope.
- Preserve dependency direction and domain boundaries.
- Prefer explicit names and mappings over framework leakage.
- Add or update tests at the layer where behavior changes.

### Before completion

- Verify that state changes pass through domain behavior.
- Verify that no inner layer depends on an outer layer.
- Verify that no domain directly accesses another domain's infrastructure.
- Run the relevant test suite and report the exact command and result.
- Update agent-facing documentation only when a durable project rule changes.

## Document Registry

| Document | Purpose | Read when |
| --- | --- | --- |
| [`docs/ddd-development-guide.md`](docs/ddd-development-guide.md) | Normative package, dependency, Command/Query, persistence, integration, and testing rules | Before any application or architecture change |

## Adding Agent-Facing Documentation

- Write every agent-facing instruction and reference document in English.
- Add a new instruction to `AGENTS.md` first when it can be stated concisely and understood without extensive context.
- Create a separate document only when the instruction set becomes too large, requires complex explanations or examples, or makes `AGENTS.md` difficult to scan quickly.
- When `AGENTS.md` becomes too long, move cohesive groups of detailed instructions into separate English documents.
- After splitting instructions, keep a concise summary and a link in `AGENTS.md`.
- Place separate documents directly under `docs/` unless this index explicitly defines a subdirectory.
- Add every separate agent-facing document to the document registry in this file.
- Maintain one canonical source for each rule; do not copy the full rule into multiple documents.
