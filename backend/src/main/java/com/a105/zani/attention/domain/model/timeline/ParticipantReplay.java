package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 한 학생의 저장된 관측·응답을 되돌려, 임의의 시각에 대해 "집계 대상이었는지, 분모에서 빠져 있었는지, 어떤 참여 상태였는지"를 답하는 객체.
 *
 * <p>실시간 경로는 Redis presence 로 접속을 판단하지만 종료된 세션에는 그 정보가 남지 않는다({@code ResolveConnectedStudentsService} 가 종료된 세션에 빈 목록을
 * 돌려준다). 그래서 사후에는 이벤트의 존재 자체를 접속의 증거로 쓴다 — 학생 브라우저는 카메라를 꺼도, 검출기가 죽어도 10초마다 이벤트를 보낸다(확정 문서 §6).
 *
 * <p>생성 시 구간을 모두 계산해 두고 이후에는 조회만 받는다. 5초 격자 × 참가자 수만큼 질문이 들어오므로 질문마다 다시 훑으면 안 된다.
 */
public final class ParticipantReplay {

    /** 이벤트 한 건이 덮는 시간. 브라우저가 10초 창을 분석해 10초마다 보낸다(확정 문서 §1). */
    private static final long SLOT_MS = 10_000L;

    private final long participantId;
    private final List<ObservationSlot> slots;
    private final List<Window> connectedWindows;
    private final List<Window> suspendedWindows;
    private final List<Window> cameraOffWindows;
    private final List<StateWindow> significantWindows;
    private final List<Window> goodWindows;
    private final List<Window> unmeasurableStateWindows;

    private ParticipantReplay(
            long participantId,
            List<ObservationSlot> slots,
            List<Window> connectedWindows,
            List<Window> suspendedWindows,
            List<Window> cameraOffWindows,
            List<StateWindow> significantWindows,
            List<Window> goodWindows,
            List<Window> unmeasurableStateWindows) {
        this.participantId = participantId;
        this.slots = slots;
        this.connectedWindows = connectedWindows;
        this.suspendedWindows = suspendedWindows;
        this.cameraOffWindows = cameraOffWindows;
        this.significantWindows = significantWindows;
        this.goodWindows = goodWindows;
        this.unmeasurableStateWindows = unmeasurableStateWindows;
    }

    public static ParticipantReplay of(
            long participantId, List<ObservationRecord> events, List<PromptRecord> prompts, TimelinePolicy policy) {
        List<ObservationSlot> slots = toSlots(events);
        List<Window> unmeasurableStateWindows = confirmedUnmeasurableWindows(slots, policy);

        List<Window> goodWindows = new ArrayList<>(runsOf(slots, DetectorOutcome::isEngaged));
        List<StateWindow> significantWindows = new ArrayList<>();
        for (Window window : unmeasurableStateWindows) {
            significantWindows.add(new StateWindow(window.startMs(), window.endMs(), AttentionState.UNMEASURABLE));
        }
        for (PromptRecord prompt : prompts) {
            AttentionState state = prompt.answer().confirmedState();
            Window window = new Window(
                    prompt.offsetMs(),
                    prompt.offsetMs() + policy.significantTtl().toMillis());
            if (state.isSignificant()) {
                significantWindows.add(new StateWindow(window.startMs(), window.endMs(), state));
            } else {
                // OK 응답은 GOOD 이다. 검출기가 저참여를 내던 구간이라도 학생이 이해했다고 답하면 GOOD 으로 본다.
                goodWindows.add(window);
            }
        }

        return new ParticipantReplay(
                participantId,
                slots,
                connectionWindows(slots, policy),
                suspensionWindows(slots, policy, DetectorOutcome::suspendsMeasurement),
                suspensionWindows(slots, policy, outcome -> outcome == DetectorOutcome.CAMERA_OFF),
                List.copyOf(significantWindows),
                merge(goodWindows),
                unmeasurableStateWindows);
    }

    public long participantId() {
        return participantId;
    }

    /** 그 시각에 접속 중이었고 연속 접속 1분을 넘겼는지. 분모 제외 여부는 보지 않는다 — 그것은 {@link #measurementSuspended(long)} 가 답한다. */
    public boolean counted(long atMs) {
        return contains(connectedWindows, atMs);
    }

    /** 측정 불가(카메라 OFF·검출기 불능)가 1분 이상 이어져 분모에서 빠져 있는지(확정 문서 §7.1). */
    public boolean measurementSuspended(long atMs) {
        return contains(suspendedWindows, atMs);
    }

    /** 위 중 카메라 OFF 가 사유인 경우. 검출기 불능은 카메라 문제가 아니라 카메라 OFF 비율에 넣지 않는다. */
    public boolean cameraOff(long atMs) {
        return contains(cameraOffWindows, atMs);
    }

    /**
     * 그 시각에 유효한 유의 상태 전부.
     *
     * <p>집합인 이유는 한 학생이 같은 시각에 두 유의 상태를 가질 수 있기 때문이다 — {@code UNMEASURABLE} 3연속으로 확정된 뒤 5분 안에 {@code CONFUSED} 로 답할 수
     * 있다. 확인 필요 비율의 분자는 학생 단위 합집합이고 응답 분포는 상태별로 세므로 둘 다 필요하다.
     */
    public Set<AttentionState> significantStatesAt(long atMs) {
        Set<AttentionState> states = EnumSet.noneOf(AttentionState.class);
        for (StateWindow window : significantWindows) {
            if (window.contains(atMs)) {
                states.add(window.state());
            }
        }
        return states;
    }

    /** 검출기 3·4단계이거나 이해 확인에 "이해했어요"로 답해 유효한 시각인지(확정 문서 §2). */
    public boolean good(long atMs) {
        return contains(goodWindows, atMs);
    }

    /**
     * 집중 점수의 분모에 들어가는 시각인지(FRD §19.2).
     *
     * <p>관측이 있어야 하고, 그 관측이 측정 자체를 못 한 값이 아니어야 하며, 참여 상태 {@code UNMEASURABLE}(3연속 확정) 구간도 아니어야 한다.
     *
     * <p>검출기 불능({@code DETECTOR_UNAVAILABLE})도 측정 불가로 본다. FRD 는 참여 상태 두 가지만 적지만 검출기 불능은 애초에 참여 상태를 만들지 않는 값이라 그 문장이 답하지
     * 않는다. 측정하지 못한 시간을 분모에 남기면 검출기가 죽은 학생의 점수가 0 으로 떨어진다 — 집단 분모에서 같은 사유로 학생을 빼는 §7.1 과 같은 이유로 여기서도 뺀다.
     *
     * <p>확정 전 {@code UNMEASURABLE} 1~2건은 남는다. 참여 상태가 아직 아니고, 동시에 {@code GOOD} 도 아니라 점수를 낮춘다. 잠시 고개를 돌린 시간이 점수를 깎는 것이며
     * FRD 문장 그대로의 동작이다.
     */
    public boolean measurable(long atMs) {
        ObservationSlot slot = slotAt(atMs);
        if (slot == null || slot.outcome().suspendsMeasurement()) {
            return false;
        }
        return !contains(unmeasurableStateWindows, atMs);
    }

    /** 그 시각을 덮는 슬롯. 관측이 없으면 {@code null} 이다. */
    public ObservationSlot slotAt(long atMs) {
        for (ObservationSlot slot : slots) {
            if (slot.covers(atMs)) {
                return slot;
            }
        }
        return null;
    }

    /** 재생에 쓰인 슬롯 전부. */
    public List<ObservationSlot> slots() {
        return slots;
    }

    /**
     * 시작 시각이 {@code [fromMs, toMs)} 안에 있는 슬롯. 겹치는 슬롯이 아니라 <b>시작하는</b> 슬롯이다.
     *
     * <p>걸친 슬롯은 자기 시작 칸에만 든다(설계 문서 §2.8). 배정 규칙을 이 메서드 하나에 가둬 둬야 30초 칸 계산에서 걸침 처리를 다시 고민할 일이 없다.
     */
    public List<ObservationSlot> slotsStartingIn(long fromMs, long toMs) {
        return slots.stream()
                .filter(slot -> slot.startMs() >= fromMs && slot.startMs() < toMs)
                .toList();
    }

    private static List<ObservationSlot> toSlots(List<ObservationRecord> events) {
        List<ObservationRecord> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(ObservationRecord::offsetMs));

        List<ObservationSlot> slots = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            long start = sorted.get(i).offsetMs();
            long end = Math.min(
                    start + SLOT_MS, i + 1 < sorted.size() ? sorted.get(i + 1).offsetMs() : Long.MAX_VALUE);
            // 같은 오프셋에 두 건이 들어오면 길이 0 슬롯이 나온다. 어떤 시각도 덮지 못하면서 연속 판정만
            // 끊으므로 버린다.
            if (end > start) {
                slots.add(new ObservationSlot(start, end, sorted.get(i).outcome()));
            }
        }
        return List.copyOf(slots);
    }

    /**
     * 연속 접속 1분을 넘긴 구간.
     *
     * <p>공백은 이벤트 시각 사이로 잰다. 확정 문서 §6.2 의 상태 키 TTL 이 마지막 이벤트 시각부터 30초라, 같은 기준을 써야 실시간과 리포트가 같은 순간에 같은 판단을 한다.
     */
    private static List<Window> connectionWindows(List<ObservationSlot> slots, TimelinePolicy policy) {
        long gap = policy.connectionGap().toMillis();
        long required = policy.requiredConnection().toMillis();

        List<Window> windows = new ArrayList<>();
        int index = 0;
        while (index < slots.size()) {
            long sessionStart = slots.get(index).startMs();
            int end = index;
            while (end + 1 < slots.size()
                    && slots.get(end + 1).startMs() - slots.get(end).startMs() <= gap) {
                end++;
            }
            long sessionEnd = slots.get(end).endMs();
            if (sessionEnd > sessionStart + required) {
                windows.add(new Window(sessionStart + required, sessionEnd));
            }
            index = end + 1;
        }
        return List.copyOf(windows);
    }

    /**
     * 조건에 맞는 관측이 이어진 길이가 정책의 지속 시간을 넘긴 구간.
     *
     * <p>제외는 1분을 기다려야 걸리지만 복귀는 즉시다(§7.1 의 비대칭). 시작을 늦추는 것은 잠깐의 흔들림을 걸러내기 위해서고, 복귀를 늦출 이유는 없다.
     */
    private static List<Window> suspensionWindows(
            List<ObservationSlot> slots, TimelinePolicy policy, OutcomePredicate matches) {
        long outage = policy.measurementOutage().toMillis();

        List<Window> windows = new ArrayList<>();
        int index = 0;
        while (index < slots.size()) {
            if (!matches.test(slots.get(index).outcome())) {
                index++;
                continue;
            }
            long runStart = slots.get(index).startMs();
            int end = index;
            while (end + 1 < slots.size()
                    && matches.test(slots.get(end + 1).outcome())
                    && slots.get(end + 1).startMs() == slots.get(end).endMs()) {
                end++;
            }
            long runEnd = slots.get(end).endMs();
            if (runEnd > runStart + outage) {
                windows.add(new Window(runStart + outage, runEnd));
            }
            index = end + 1;
        }
        return List.copyOf(windows);
    }

    /**
     * 참여 상태 {@code UNMEASURABLE} 이 확정된 구간.
     *
     * <p>검출기 {@code UNMEASURABLE} 이 연속 3회 이어지면 그 세 번째 관측 시각에 확정하고 5분 유효하다(확정 문서 §2). 연속이 더 이어지면 관측마다 다시 확정해 유효 기간이 함께
     * 밀린다.
     */
    private static List<Window> confirmedUnmeasurableWindows(List<ObservationSlot> slots, TimelinePolicy policy) {
        long ttl = policy.significantTtl().toMillis();

        List<Window> windows = new ArrayList<>();
        int run = 0;
        for (int i = 0; i < slots.size(); i++) {
            boolean continues =
                    i > 0 && slots.get(i).startMs() == slots.get(i - 1).endMs();
            if (slots.get(i).outcome() == DetectorOutcome.UNMEASURABLE) {
                run = continues ? run + 1 : 1;
            } else {
                run = 0;
                continue;
            }
            if (run >= policy.unmeasurableRunLength()) {
                windows.add(new Window(slots.get(i).startMs(), slots.get(i).startMs() + ttl));
            }
        }
        return merge(windows);
    }

    /** 겹치거나 맞닿은 구간을 하나로 합친다. 조회가 구간 목록을 그대로 훑으므로 목록이 짧을수록 좋다. */
    private static List<Window> merge(List<Window> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }
        List<Window> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparingLong(Window::startMs));

        List<Window> merged = new ArrayList<>();
        long start = sorted.getFirst().startMs();
        long end = sorted.getFirst().endMs();
        for (Window window : sorted.subList(1, sorted.size())) {
            if (window.startMs() <= end) {
                end = Math.max(end, window.endMs());
            } else {
                merged.add(new Window(start, end));
                start = window.startMs();
                end = window.endMs();
            }
        }
        merged.add(new Window(start, end));
        return List.copyOf(merged);
    }

    private static List<Window> runsOf(List<ObservationSlot> slots, OutcomePredicate matches) {
        List<Window> windows = new ArrayList<>();
        for (ObservationSlot slot : slots) {
            if (matches.test(slot.outcome())) {
                windows.add(new Window(slot.startMs(), slot.endMs()));
            }
        }
        return windows;
    }

    private static boolean contains(List<Window> windows, long atMs) {
        for (Window window : windows) {
            if (window.contains(atMs)) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface OutcomePredicate {
        boolean test(DetectorOutcome outcome);
    }

    private record Window(long startMs, long endMs) {
        boolean contains(long atMs) {
            return atMs >= startMs && atMs < endMs;
        }
    }

    private record StateWindow(long startMs, long endMs, AttentionState state) {
        boolean contains(long atMs) {
            return atMs >= startMs && atMs < endMs;
        }
    }
}
