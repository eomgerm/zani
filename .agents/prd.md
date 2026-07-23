# ZANI Product Requirements

> Stable product definition for agents. Volatile details — concrete thresholds,
> counts, metric targets, phase breakdowns, data-model fields, and code/UI
> examples — are intentionally omitted here; treat those as implementation
> decisions that live in code and design docs, not in this reference.

## 1. Product Summary

ZANI is a WebRTC-based real-time remote lecture platform that combines
attention- and engagement-signal detection, quick student check prompts,
instructor group alerts, and post-class transcript-based review
recommendations.

ZANI does not judge or grade whether an individual student understands the
material. Instead, when it detects signals that suggest a check-in may be
useful, it shows the student a short self-report prompt; when the same pattern
appears across the group, it tells the instructor that this may be a good moment
to intervene. After class, ZANI combines the transcript with class events to
produce per-student review recommendation candidates.

## 2. Target Users

**Primary**

- University instructors
- Bootcamp instructors
- University students and adult learners attending live online classes

**Secondary**

- Education operations managers
- Curriculum designers
- Tutors and teaching assistants

## 3. Problem Statement

In live remote classes, instructors cannot easily tell in the moment when
students are losing focus, struggling with a concept, or drifting from the flow
of the lesson. Students often hesitate to ask questions during class, so their
difficulty goes unexpressed, and after class they struggle to identify which
segments they should review.

Existing video-conferencing tools provide video, chat, recording, and some AI
summaries, but they do not connect real-time class-flow signals to personalized
post-class review candidates.

## 4. Product Goals

1. Help instructors avoid missing moments when the group's focus drops or a
   concept clearly needs reinforcement.
2. Let students express their state with a single, low-friction action.
3. Combine the post-class transcript with an event timeline to produce
   per-student review recommendation candidates.
4. Use camera-based signals only for learning support — never for grading,
   attendance, or disciplinary purposes.
5. Minimize storage of raw video, and make per-class consent and opt-out
   explicit.

## 5. Non-Goals

The product does not:

- Produce per-student comprehension scores.
- Rank students by attention or focus.
- Determine comprehension from facial expression, gaze, or posture alone.
- Automate grading, attendance, or discipline.
- Serve minors or K-12 operations.
- Replace a full LMS.
- Build an advanced per-student knowledge-tracing model.
- Store every student's raw video or run server-side facial analysis on it.

## 6. Core User Scenarios

**A. The instructor monitors real-time class flow.** The instructor creates a
WebRTC classroom and teaches. On joining, students separately choose whether to
participate in attention-signal analysis, independent of camera/microphone
permissions. During class, when a student shows check-needed signals, that
student receives a quick check prompt. When check-needed signals appear across a
meaningful share of participants within a recent window, the instructor
dashboard raises an alert, and the instructor can respond (for example, by
adding an example or asking a short confirming question).

**B. The student responds quickly.** During class, a student may receive a short
prompt asking whether they are following along, with a few one-tap options. The
response is not an individual evaluation; it feeds class-flow improvement and the
generation of review recommendation candidates.

**C. The student reviews recommendations after class.** When class ends, the
system combines the transcript, key concepts, per-student events, and quick-check
responses into review recommendation candidates. Each candidate shows the
related segment and the reason it was suggested, and links back so the student
can rewatch it.

## 7. Core Capabilities

At the concept level, ZANI covers:

- **Real-time class** — classroom creation, instructor/student join,
  audio/video, screen sharing, chat, emoji reactions, hand-raising, and a
  participant list.
- **Consent flow** — per-class, explicitly separated consent for camera/mic
  versus attention-signal analysis. Students can decline analysis and still
  attend, and can turn analysis off at any time during class. Raw video storage
  defaults to off.
- **Attention-signal detection** — treats gaze/posture and class-activity
  signals as triggers indicating that a check-in *may* be useful, never as a
  comprehension verdict. Analysis is performed in the browser where possible;
  the server receives low-level events rather than raw video frames.
- **Student check prompt** — a short, rate-limited self-report prompt shown when
  attention-drop and/or class-activity signals accumulate. Responses inform
  class flow and post-class review candidates; non-responses are only a weak
  signal.
- **Instructor alert** — raised when check-needed signals appear across a
  meaningful share of participants within a recent window, and rate-limited to
  avoid alert fatigue. Participants who declined camera analysis are still
  counted, using class-activity signals only.
- **Post-class transcript and summary** — transcript, time-segmented summaries,
  key-concept extraction, question/chat summary, an event timeline, and an
  instructor report.
- **Personalized review recommendations** — per-student review *candidates*
  (not weakness verdicts), each shown with its supporting reasons and a rewatch
  link.

## 8. Functional Requirements

**Student**

- Can join a class.
- Can choose, per class, whether to participate in analysis, and can turn it off
  during class.
- Can respond to quick check prompts.
- Can view personal review recommendation candidates after class, including the
  reasons and rewatch links.

**Instructor**

- Can create a classroom and run a live class.
- Can see chat, hand-raising, and emoji reactions.
- Can receive group check-needed alerts, with the alert type and a suggested
  intervention.
- Can view the post-class report: transcript, summaries, and the event timeline.

**System**

- Extracts attention/engagement features in the browser.
- Stores events rather than raw video.
- Stores per-student prompt responses with timestamps.
- Generates a transcript after class and links transcript chunks to events by
  time.
- Generates per-student review recommendation candidates.

## 9. Data Model (Entities)

The domain is organized around these entities (fields are an implementation
detail and are not fixed here): Session, Participant, Event, CheckPrompt,
TranscriptChunk, and ReviewRecommendation.

## 10. Privacy and Safety Requirements

- Analysis participation is requested explicitly, per class.
- Declining analysis must not block class participation.
- Raw student video storage defaults to off; storing it requires separate
  consent.
- Analysis results are never used for grading, attendance, or discipline.
- No per-student scores, rankings, or grades are produced.
- Review recommendations are presented as candidates, always with their reasons.
- Camera-based signals are used only as auxiliary signals.
- Students can always see their own analysis-participation state.
- Operators manage access permissions and access logs.

## 11. Product Definition

ZANI is a WebRTC lecture platform for university and bootcamp live classes that
detects check-needed signals from attention and engagement, shows students a
quick check prompt, tells instructors when collective signals grow enough to
warrant intervention, and — after class — combines the transcript with class
events to offer per-student review recommendation candidates. The definition is
technically buildable, keeps privacy and ethical risk low, and stays
differentiated from existing conferencing and lecture platforms.
