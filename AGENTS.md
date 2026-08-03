# AI Agent Documentation Index

## Scope

This file is the single entry point for AI agents working anywhere in the ZANI
monorepo (`backend`, `fe`, `ai`, code review, and infrastructure work).

When a more specific instruction lives closer to the file being changed, that
closer instruction wins for that file. Otherwise this index and the guides in
its [Document Registry](#document-registry) apply.

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
[`.agents/prd.md`](.agents/prd.md). For feature-level MVP requirements, user
journeys, and acceptance detail, read [`.agents/frd.md`](.agents/frd.md).

## Instruction Precedence

Apply instructions in this order:

1. The current user request.
2. A more specific instruction located closer to the file being changed.
3. This root `AGENTS.md`.
4. The guides listed in the `.agents/` document registry below.
5. Established conventions in the affected domain.

If two instructions conflict and precedence does not resolve the conflict, stop
and ask the user.

### No Per-Module `AGENTS.md`

This repository deliberately has no `backend/AGENTS.md`, `fe/AGENTS.md`, or
`ai/AGENTS.md`, and none should be added. Every agent-facing document lives flat
under `.agents/`, so a per-module file could only restate what an `.agents/`
guide already owns — which would break the one-canonical-source rule below.
Precedence rule 2 is satisfied by the domain guides in `.agents/`, not by files
placed next to the code.

Do not read the absence of these files as a gap to fill. Module-level rules live
here:

| Module | Where the rules live |
| --- | --- |
| `backend` | [`.agents/ddd-development-guide.md`](.agents/ddd-development-guide.md), plus [`.agents/flyway-migration-guide.md`](.agents/flyway-migration-guide.md) for migrations |
| `fe` | Layer rules are enforced by `code-review/config/ddd-rules.json`, which the review bot checks on every merge request — `fe/src/domains/*` splits into `domain` / `application` / `infrastructure` / `presentation`, and `domain` may not import React, Next, Axios, TanStack Query, Zustand, or touch browser storage. **No prose guide explains the reasoning yet**, so for judgement calls the rules do not cover — such as when something belongs in `features/` or `shared/` rather than a domain — follow the existing structure in the affected domain and say that is what you did. Feature-specific guides: [`.agents/attention-coaching-context.md`](.agents/attention-coaching-context.md) for attention and coaching, [`.agents/livekit-frontend-guide.md`](.agents/livekit-frontend-guide.md) for real-time media. |
| `ai` | [`.agents/ai-remote-l40s-guide.md`](.agents/ai-remote-l40s-guide.md) and [`.agents/ai-experiment-results-guide.md`](.agents/ai-experiment-results-guide.md) |

The `fe` prose gap is real, not an oversight in this table. Writing that guide is
separate work. The machine-checked layer rules already hold in the meantime —
read `code-review/config/ddd-rules.json` before assuming the frontend has no
structural constraints.

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
- Attention signals, student check prompts, student state decisions, or
  instructor coaching tips →
  [`.agents/attention-coaching-context.md`](.agents/attention-coaching-context.md).
- Producing a recording manifest or changing the finalize-recording worker →
  [`.agents/media-finalize-recording-guide.md`](.agents/media-finalize-recording-guide.md).
- Calling SSAFY GMS for transcription or an LLM, or deciding what may be sent to
  it → [`.agents/gms-integration-guide.md`](.agents/gms-integration-guide.md).
- Training on the remote L40S server →
  [`.agents/ai-remote-l40s-guide.md`](.agents/ai-remote-l40s-guide.md).
- Reading or publishing training metrics →
  [`.agents/ai-experiment-results-guide.md`](.agents/ai-experiment-results-guide.md).

Do not infer a project-wide convention from a single file when a guide defines a
different rule.

## Document Registry

All agent-facing documents live flat under `.agents/`. **Type** distinguishes
normative *Guidance* (rules that take precedence) from *Reference* (context and
implementation detail an agent should read before working).

| Document | Type | Purpose | Read when |
| --- | --- | --- | --- |
| [`.agents/prd.md`](.agents/prd.md) | Reference | ZANI product definition, users, goals, scope, privacy requirements | Before work that depends on product intent or scope |
| [`.agents/frd.md`](.agents/frd.md) | Reference | MVP feature requirements, user journeys, acceptance detail, core data concepts, error handling | Before implementing or changing a user-facing feature |
| [`.agents/ddd-development-guide.md`](.agents/ddd-development-guide.md) | Guidance | Normative backend package, dependency, Command/Query, persistence, integration, and testing rules | Before any backend application or architecture change |
| [`.agents/flyway-migration-guide.md`](.agents/flyway-migration-guide.md) | Guidance | Flyway authoring, versioning, deployment, recovery, and verification rules | Before creating, changing, or deploying a database migration |
| [`.agents/livekit-integration-context.md`](.agents/livekit-integration-context.md) | Guidance | LiveKit integration baseline: precedence, fixed system boundaries, safety rules | Before any LiveKit / real-time media work |
| [`.agents/livekit-overview.md`](.agents/livekit-overview.md) | Reference | Shared overview of sessions, LiveKit rooms, media, and recording | First, when starting LiveKit work |
| [`.agents/livekit-frontend-guide.md`](.agents/livekit-frontend-guide.md) | Reference | Frontend LiveKit implementation guide | Frontend LiveKit work |
| [`.agents/livekit-backend-guide.md`](.agents/livekit-backend-guide.md) | Reference | Backend LiveKit implementation guide and target contracts | Backend LiveKit work |
| [`.agents/attention-coaching-context.md`](.agents/attention-coaching-context.md) | Guidance | Normative attention and coaching contracts: detector outputs, student states, the 10-second decision, student prompts, server payloads, instructor triggers and tip wording | Before any attention, check-prompt, or coaching work |
| [`.agents/media-finalize-recording-guide.md`](.agents/media-finalize-recording-guide.md) | Guidance | Recording finalize worker contract: manifest schema v1, layout derivation, audio mixing, output guarantees, exit codes | Before producing a recording manifest or changing the finalize worker |
| [`.agents/gms-integration-guide.md`](.agents/gms-integration-guide.md) | Guidance | Measured SSAFY GMS gateway limits, request and error contracts, capability boundary, and normative pseudonymisation rules for anything sent to GMS | Before adding or changing any GMS transcription or LLM call |
| [`.agents/ai-remote-l40s-guide.md`](.agents/ai-remote-l40s-guide.md) | Reference | Remote L40S training environment, run procedure, and idle-cull limits | Before training on the remote server |
| [`.agents/ai-experiment-results-guide.md`](.agents/ai-experiment-results-guide.md) | Guidance | Where training metrics live, how to read them, and what must never enter the results branch | Before reading or publishing training metrics |

## Adding Agent-Facing Documentation

- Write every **new** agent-facing document in English. Several existing
  documents are written in Korean; they stay as they are. Do not open a revision
  whose purpose is translation. When you edit a Korean document, keep writing in
  Korean so the file stays internally consistent.
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
