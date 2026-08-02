# SSAFY GMS Integration Guide

SSAFY GMS is the only inference provider ZANI calls. It fronts several upstream
vendors behind one host and one credential. This document records what the
gateway actually does — measured against the live service, not copied from
vendor documentation — and the rules every GMS caller must follow.

Read this before adding or changing any GMS call: real-time coaching tips,
post-class transcription, or post-class LLM analysis.

## 1. Precedence

The measured limits in §4 are properties of the gateway, not of our code. Treat
them as fixed. When a vendor document and this guide disagree, this guide wins
for anything reached through `gms.ssafy.io`, because the gateway imposes limits
the vendors do not.

The pseudonymisation rules in §9 are normative. Do not send participant
identity to GMS in any other form.

## 2. The gateway is model-scoped

Every request must name a model, in the path or in the body. The gateway parses
the model before routing and rejects anything it cannot find one in.

| Request | Response |
| --- | --- |
| `GET /v1/models` | `400 [GMS 에러] Model not found in request for domain api.openai.com` |
| `GET /v1beta/files` | `400 [GMS 에러] Model not found in request for domain generativelanguage.googleapis.com` |
| `POST /upload/v1beta/files` | same as above |

Two consequences:

- **Model discovery is impossible.** There is no way to enumerate available
  models. Model names must be taken from the SSAFY GMS console and pinned in
  configuration.
- **The Gemini File API is unreachable.** Uploading a file once and referencing
  it by handle is not available. All media must travel inline in the request
  body, which §4 caps hard.

## 3. Upstreams, endpoints, and auth

The base URL selects the upstream. Authentication differs per upstream, so a
single `RestClient` cannot serve both.

| Upstream | Base | Auth header |
| --- | --- | --- |
| OpenAI | `https://gms.ssafy.io/gmsapi/api.openai.com` | `Authorization: Bearer <key>` |
| Google | `https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com` | `x-goog-api-key: <key>` |

The same credential works for both.

Models currently in use:

| Model | Endpoint | Used by |
| --- | --- | --- |
| `whisper-1` | `POST {openai}/v1/audio/transcriptions` (multipart) | real-time clip transcription, post-class batch transcription |
| `gpt-5.4-mini` | `POST {openai}/v1/chat/completions` (JSON) | real-time tip concept extraction |

## 4. Hard limits

These are the most important numbers in this document. Both were found by
bisecting request sizes against the live gateway.

### 4.1 JSON request body — 102,400 bytes

| Body size | Result |
| --- | --- |
| 102,400 B | accepted |
| 102,401 B | rejected |

The limit is exactly 100 KiB and applies to **both** LLM upstreams. Measured
byte-exact on `POST {openai}/v1/chat/completions`; confirmed on the Google path
as well (90,434 B accepted, 102,846 B rejected).

**The failure mode is silent truncation, not an error about size.** The gateway
forwards a request whose body has been cut, so the upstream reports a missing
field:

- OpenAI path — `[GMS 에러] Model not found in request for domain api.openai.com`
- Google path — `GenerateContentRequest.contents: contents is not specified`

Both messages are misleading. A well-formed request that names a model can
produce "model not found" purely because it was too large. When a GMS call
fails with a missing-field error, check the body size before checking the body.

**A model's advertised context window is not reachable through the gateway.**
`gemini-3.5-flash` documents a 1,000,000-token input limit, but 100 KiB of
Korean text is roughly 34,000 characters — on the order of 25,000–30,000 tokens.
The gateway body limit, not the model, is the effective ceiling. Choosing a
larger-context model does not change it.

### 4.2 Multipart upload — 26,214,400 bytes

| Upload size | Result |
| --- | --- |
| 24,576,000 B (24 MB) | reaches upstream format validation |
| 26,624,000 B (26 MB) | `413: Maximum content size limit (26214400) exceeded` |

Exactly 25 MiB, and unlike §4.1 this one returns an honest `413`. Multipart is
**270 times more permissive than JSON**, so audio belongs in multipart and never
base64-encoded into a JSON body.

## 5. Rate limits

Reported by response headers on the OpenAI path:

```
X-Ratelimit-Limit-Requests: 30000
X-Ratelimit-Limit-Tokens:   180000000
X-Ratelimit-Reset-Requests: 2ms
```

Twelve concurrent chat completions all returned `200` in 1.0–1.7 s. Rate limits
are not a practical constraint at ZANI's scale. Handle `429` defensively, but do
not design around it.

## 6. Chat completion request constraints

- `max_tokens` is rejected with `400`. Use `max_completion_tokens`.
- `temperature: 0` is accepted.
- `response_format` with `json_schema` and `strict: true` is supported.
  `maxLength`, `minimum`, and `maximum` are accepted inside the schema. Whether
  the model enforces them is unverified — validate responses anyway.
- Pair `strict: true` with `additionalProperties: false`, otherwise the model
  can leave the schema.

## 7. Error shapes

Errors arrive nested twice. The outer envelope is the gateway's, the inner one
is the upstream's:

```json
{"message":"[OpenAI 에러] ...","statusCode":400,
 "error":{"error":{"message":"...","type":"invalid_request_error","code":"..."}}}
```

Parse defensively: `error.error.message` may be absent, and on some gateway
failures `error` is an HTML string rather than an object.

A truncated response is reported as `finish_reason: length`, and the JSON body
is cut mid-structure so parsing fails too. Log the finish reason so the cause is
distinguishable from a schema violation.

## 8. Measured latency and volume

| Measurement | Value |
| --- | --- |
| `whisper-1`, 300-second clip | 14–31 s, varying with GMS load |
| `gpt-5.4-mini`, one concept slot, ~100-token prompt | 1.2–1.5 s |
| Tip token consumption | 50 completion tokens with evidence, 25 without |
| Korean lecture transcript density | 5.55 characters per second (5-minute lecture produced 1,665 characters) |
| Image cost | a 64x64 PNG billed 1,089 image tokens |

Latency for clips longer than 300 seconds has **not** been measured. Do not
assume it scales linearly; measure before relying on it for the 8-hour
post-class SLA (`AI-006`).

### 8.1 Derived planning figures

At the current encoder setting (`Mp3TranscriptionAudioEncoder.BITRATE_KBPS = 64`,
mono — 8,000 bytes per second):

```
26,214,400 B / 8,000 B/s = 3,276 s = 54.6 minutes of audio per upload
```

Leave headroom for ID3 frames and the multipart envelope; 45-minute chunks are
a safe working figure. A three-hour track needs four uploads.

For LLM calls, 100 KiB of Korean transcript is about 34,000 characters, roughly
102 minutes of speech at the measured density. After the system prompt, events,
and notes are added, plan for **80–90 minutes of transcript per call**. A
three-hour lecture transcript is about 180 KB and does not fit in one call.

## 9. Pseudonymisation rules (normative)

Three layers already exist and must not be bypassed:

| Layer | Value | Defined by |
| --- | --- | --- |
| LiveKit identity, client-facing events | `p-{sessionParticipantId}` | `SessionParticipantIdentity` |
| Recording artefacts, manifests, file paths, Egress outbox payloads | `instructor`, `student-001` … | `RecordingAlias` |
| `recording_files` speaker column | `session_participant_id` (internal FK) | schema |

The rules for transcripts and GMS calls:

1. **Store `sessionParticipantId` as the transcript speaker.** The UI must
   resolve real display names, which needs a value that joins back to
   `SessionParticipant`.
2. **Substitute `RecordingAlias` when sending to GMS, and send no session
   identifier at all.** Storage wants a value that can be resolved; egress wants
   one that cannot. They conflict, so they are different values. Reuse
   `resolveAlias` rather than inventing a scheme — its ordering is stable
   because participant ids are immutable.
3. Never send names, e-mail addresses, Google subjects, or member ids.

## 10. Capability boundary

| Modality | Status |
| --- | --- |
| Text in, text out | Available |
| Audio in, text out | Available — `whisper-1`, multipart, up to 25 MiB |
| Image in, text out | Available on multimodal models. Verified: `gemini-3.5-flash` correctly identified a solid-red 64x64 PNG |
| Video in | **Not usable.** Deferred, see below |

`gpt-5.4-mini` is not documented as multimodal. Models the console lists as
multimodal include `gpt-4o`, `gpt-4o-mini`, `gemini-3.5-flash`,
`gemini-2.5-flash`, `claude-sonnet-4-6`, and `claude-opus-4-1-20250805`.

Video analysis is **deferred, not rejected on model capability**. The model can
see images; the gateway cannot carry video:

- the File API is unreachable (§2), so large media cannot be uploaded and
  referenced;
- inline media must fit the 100 KiB JSON body (§4.1), leaving roughly 72 KB
  after base64 expansion — less than a single high-quality video frame.

Ten-to-fifteen-minute video chunks are therefore not deliverable to GMS by any
available route. Non-verbal signals such as pointing or gesture emphasis cannot
be derived. Post-class analysis uses the transcript plus event timestamps, which
carry the information the reports need.

## 11. Known limitations

- **Real names inside transcript text are not masked.** When an instructor says
  a student's name aloud, that name reaches GMS in the transcript body.
  Pseudonymising speaker labels does not prevent this, and it already happens on
  the real-time tip path. Masking against the participant roster was considered
  and rejected for the MVP: homonyms and inflected Korean address forms make it
  unreliable, and the cost is high relative to the exposure. Revisit if the
  processing basis changes.
- Whether the model enforces `maxLength` / `minimum` / `maximum` inside a strict
  schema is unverified (§6).
- Latency above 300-second clips is unmeasured (§8).

## 12. Open items

| Item | Owner |
| --- | --- |
| Measure `whisper-1` latency for 45-minute chunks against the 8-hour SLA | S15P11A105-247 |
| Split post-class LLM analysis across calls — a three-hour transcript exceeds one request | S15P11A105-248 |
| Revise FRD §17 — §17.1 and §17.3 assume video chunks reach GMS; §17.4 assumes non-verbal signals are available | product decision |
