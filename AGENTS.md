# AI Agent Documentation Index

## Scope

This file is the single entry point for AI agents working anywhere in the ZANI
monorepo (`backend`, `fe`, `ai`, code review, and infrastructure work).

When a more specific instruction lives closer to the file being changed, that
closer instruction wins for that file. Otherwise this index and the guides in
its [Document Registry](#document-registry) apply.

All documentation intended to instruct AI agents MUST be written in English.

## Project Introduction

ZANI is a WebRTC-based real-time remote lecture platform that pairs live class
delivery with attention-signal detection, quick student check prompts,
instructor group alerts, and post-class transcript-based review
recommendations.

The repository is a monorepo:

- `backend/` — Spring Boot application (session, auth, real-time coordination)
- `fe/` — Next.js frontend
- `ai/` — Python services for signal and transcript processing

For the product definition, users, goals, and scope, read
[`.agents/prd.md`](.agents/prd.md).

## Instruction Precedence

Apply instructions in this order:

1. The current user request.
2. A more specific instruction located closer to the file being changed.
3. This root `AGENTS.md`.
4. The guides listed in the `.agents/` document registry below.
5. Established conventions in the affected domain.

If two instructions conflict and precedence does not resolve the conflict, stop
and ask the user.

## Repository Context

| Module | Language | Framework / Notes |
| --- | --- | --- |
| `backend` | Java 21 | Spring Boot, Gradle, single-module modular monolith, root package `com.a105.zani` |
| `fe` | TypeScript | Next.js |
| `ai` | Python | Signal and transcript processing services |

## Required Reading

Before you start work, read the guides relevant to your task:

- Backend application code, package structure, or architecture changes →
  [`.agents/ddd-development-guide.md`](.agents/ddd-development-guide.md), then
  inspect existing files in the affected business domain.
- Creating, changing, or deploying a database migration →
  [`.agents/flyway-migration-guide.md`](.agents/flyway-migration-guide.md).
- Any LiveKit / real-time media integration →
  [`.agents/livekit-integration-context.md`](.agents/livekit-integration-context.md)
  first, then the relevant `livekit-*` guide(s).

Do not infer a project-wide convention from a single file when a guide defines a
different rule.

## Document Registry

All agent-facing documents live flat under `.agents/`. **Type** distinguishes
normative *Guidance* (rules that take precedence) from *Reference* (context and
implementation detail an agent should read before working).

| Document | Type | Purpose | Read when |
| --- | --- | --- | --- |
| [`.agents/prd.md`](.agents/prd.md) | Reference | ZANI product definition, users, goals, scope, privacy requirements | Before work that depends on product intent or scope |
| [`.agents/ddd-development-guide.md`](.agents/ddd-development-guide.md) | Guidance | Normative backend package, dependency, Command/Query, persistence, integration, and testing rules | Before any backend application or architecture change |
| [`.agents/flyway-migration-guide.md`](.agents/flyway-migration-guide.md) | Guidance | Flyway authoring, versioning, deployment, recovery, and verification rules | Before creating, changing, or deploying a database migration |
| [`.agents/livekit-integration-context.md`](.agents/livekit-integration-context.md) | Guidance | LiveKit integration baseline: precedence, fixed system boundaries, safety rules | Before any LiveKit / real-time media work |
| [`.agents/livekit-overview.md`](.agents/livekit-overview.md) | Reference | Shared overview of sessions, LiveKit rooms, media, and recording | First, when starting LiveKit work |
| [`.agents/livekit-frontend-guide.md`](.agents/livekit-frontend-guide.md) | Reference | Frontend LiveKit implementation guide | Frontend LiveKit work |
| [`.agents/livekit-backend-guide.md`](.agents/livekit-backend-guide.md) | Reference | Backend LiveKit implementation guide and target contracts | Backend LiveKit work |

## Adding Agent-Facing Documentation

- Write every agent-facing instruction and reference document in English.
- Add a new instruction to this `AGENTS.md` first when it can be stated
  concisely and understood without extensive context.
- Create a separate document only when the instruction set becomes too large,
  requires complex explanations or examples, or makes this index hard to scan.
- Place every separate agent-facing document flat under `.agents/`. Use a clear
  prefix when documents form a group (for example `livekit-`).
- Add every separate agent-facing document to the Document Registry above, with
  its Type.
- Maintain one canonical source for each rule; do not copy the full rule into
  multiple documents.
