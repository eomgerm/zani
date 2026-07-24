#!/usr/bin/env python3
"""finalize_recording.py 순수 로직 단위테스트 (ffmpeg 불필요, 로컬 실행 가능).

실행: python3 -m unittest discover -s infrastructure/media/tests
     또는 python3 infrastructure/media/tests/test_finalize_recording.py
"""

import importlib.util
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).resolve().parent.parent / "finalize_recording.py"
_spec = importlib.util.spec_from_file_location("finalize_recording", _MODULE_PATH)
fr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fr)


def track(**overrides):
    """manifest 원본 형태(참가자 필드명). validate_manifest 입력용."""
    base = {
        "participant_identity": "instructor",
        "participant_role": "INSTRUCTOR",
        "source": "CAMERA",
        "relative_path": "raw/instructor-camera-001.webm",
        "offset_ms": 0,
        "duration_ms": 60000,
    }
    base.update(overrides)
    return base


def ntrack(**overrides):
    """validate_manifest 가 반환하는 정규화 형태. select/interval/filtergraph 함수 입력용."""
    base = {
        "identity": "instructor",
        "role": "INSTRUCTOR",
        "source": "CAMERA",
        "relative_path": "raw/instructor-camera-001.webm",
        "path": "/tmp/instructor-camera-001.webm",
        "offset_ms": 0,
        "duration_ms": 60000,
        "sha256": None,
    }
    base.update(overrides)
    return base


def manifest(tracks):
    return {
        "schema_version": 1,
        "session_id": "s-1",
        "timeline_started_at": "2026-07-24T05:00:00Z",
        "tracks": tracks,
    }


class ValidateManifestTest(unittest.TestCase):
    def setUp(self):
        self.session = tempfile.mkdtemp()

    def test_valid_manifest_returns_tracks(self):
        result = fr.validate_manifest(manifest([track()]), self.session)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["source"], "CAMERA")

    def test_rejects_wrong_schema_version(self):
        m = manifest([track()])
        m["schema_version"] = 2
        self._assert_code(fr.EXIT_MANIFEST, m)

    def test_rejects_bad_source(self):
        self._assert_code(fr.EXIT_MANIFEST, manifest([track(source="WEBCAM")]))

    def test_rejects_bad_role(self):
        self._assert_code(fr.EXIT_MANIFEST, manifest([track(participant_role="TEACHER")]))

    def test_rejects_missing_session_id(self):
        m = manifest([track()])
        del m["session_id"]
        self._assert_code(fr.EXIT_MANIFEST, m)

    def test_rejects_negative_offset(self):
        self._assert_code(fr.EXIT_MANIFEST, manifest([track(offset_ms=-1)]))

    def test_rejects_zero_duration(self):
        self._assert_code(fr.EXIT_MANIFEST, manifest([track(duration_ms=0)]))

    def test_rejects_empty_tracks(self):
        self._assert_code(fr.EXIT_MANIFEST, manifest([]))

    def test_excludes_student_camera(self):
        tracks = [
            track(),
            track(participant_identity="student-001", participant_role="STUDENT",
                  source="CAMERA", relative_path="raw/student-001-camera.webm"),
        ]
        result = fr.validate_manifest(manifest(tracks), self.session)
        self.assertTrue(all(not (t["role"] == "STUDENT" and t["source"] == "CAMERA") for t in result))
        self.assertEqual(len(result), 1)

    def _assert_code(self, code, m):
        with self.assertRaises(fr.WorkerError) as ctx:
            fr.validate_manifest(m, self.session)
        self.assertEqual(ctx.exception.code, code)


class ResolvePathTest(unittest.TestCase):
    def setUp(self):
        self.session = tempfile.mkdtemp()

    def test_normal_relative_path(self):
        resolved = fr.resolve_track_path(self.session, "raw/a.webm")
        self.assertTrue(str(resolved).startswith(str(Path(self.session).resolve())))

    def test_rejects_parent_escape(self):
        with self.assertRaises(fr.WorkerError) as ctx:
            fr.resolve_track_path(self.session, "../../etc/passwd")
        self.assertEqual(ctx.exception.code, fr.EXIT_MANIFEST)

    def test_rejects_absolute_path(self):
        with self.assertRaises(fr.WorkerError) as ctx:
            fr.resolve_track_path(self.session, "/etc/passwd")
        self.assertEqual(ctx.exception.code, fr.EXIT_MANIFEST)


class IntervalTest(unittest.TestCase):
    def test_screen_intervals_sorted(self):
        tracks = [
            ntrack(source="SCREEN_SHARE", offset_ms=40000, duration_ms=15000),
            ntrack(source="SCREEN_SHARE", offset_ms=10000, duration_ms=20000),
        ]
        self.assertEqual(
            fr.instructor_screen_intervals(tracks),
            [(10000, 30000), (40000, 55000)],
        )

    def test_overlapping_screen_intervals_rejected(self):
        tracks = [
            ntrack(source="SCREEN_SHARE", offset_ms=10000, duration_ms=20000),
            ntrack(source="SCREEN_SHARE", offset_ms=25000, duration_ms=10000),
        ]
        with self.assertRaises(fr.WorkerError) as ctx:
            fr.instructor_screen_intervals(tracks)
        self.assertEqual(ctx.exception.code, fr.EXIT_MANIFEST)

    def test_total_duration(self):
        tracks = [ntrack(offset_ms=0, duration_ms=60000),
                  ntrack(source="MICROPHONE", offset_ms=3500, duration_ms=59000)]
        self.assertEqual(fr.total_duration_ms(tracks), 62500)


class SelectTest(unittest.TestCase):
    def test_select_video_screens_before_cameras(self):
        tracks = [
            ntrack(source="CAMERA", offset_ms=0, duration_ms=60000),
            ntrack(source="SCREEN_SHARE", offset_ms=10000, duration_ms=20000),
            ntrack(role="STUDENT", identity="student-001",
                   source="MICROPHONE", offset_ms=0, duration_ms=60000),
        ]
        video = fr.select_video_tracks(tracks)
        self.assertEqual([t["source"] for t in video], ["SCREEN_SHARE", "CAMERA"])

    def test_select_audio(self):
        tracks = [
            ntrack(source="CAMERA"),
            ntrack(source="MICROPHONE"),
            ntrack(source="SCREEN_SHARE_AUDIO"),
        ]
        audio = fr.select_audio_tracks(tracks)
        self.assertEqual([t["source"] for t in audio], ["MICROPHONE", "SCREEN_SHARE_AUDIO"])

    def test_has_required_video(self):
        self.assertFalse(fr.has_required_video([]))
        self.assertTrue(fr.has_required_video([ntrack()]))


class FilterComplexTest(unittest.TestCase):
    def test_layout_with_two_screen_intervals(self):
        # 사용자 예시: 0-60s, 화면공유 10-30, 40-55
        video = [
            track(source="SCREEN_SHARE", offset_ms=10000, duration_ms=20000),
            track(source="SCREEN_SHARE", offset_ms=40000, duration_ms=15000),
            track(source="CAMERA", offset_ms=0, duration_ms=60000),
        ]
        audio = [track(source="MICROPHONE", offset_ms=0, duration_ms=60000)]
        intervals = [(10000, 30000), (40000, 55000)]
        graph = fr.build_filter_complex(video, audio, intervals, 60000)

        self.assertIn("color=c=black:s=1280x720:r=30:d=60.000[vb0]", graph)
        # 두 화면 구간 게이팅
        self.assertIn("enable='between(t,10.000,30.000)'", graph)
        self.assertIn("enable='between(t,40.000,55.000)'", graph)
        # 카메라 메인은 화면 없을 때만, PiP 는 화면 있을 때만
        screen_sum = "between(t,10.000,30.000)+between(t,40.000,55.000)"
        self.assertIn(f"lt({screen_sum},1)", graph)
        self.assertIn(f"gte({screen_sum},1)", graph)
        self.assertTrue(graph.endswith("[a]"))
        self.assertIn("[v]", graph)

    def test_no_screen_defaults_camera_main(self):
        video = [track(source="CAMERA", offset_ms=0, duration_ms=30000)]
        audio = [track(source="MICROPHONE", offset_ms=0, duration_ms=30000)]
        graph = fr.build_filter_complex(video, audio, [], 30000)
        self.assertIn("lt(0,1)", graph)   # 항상 카메라 메인
        self.assertIn("gte(0,1)", graph)  # PiP 는 절대 표시 안 됨

    def test_screen_audio_volume_lowered(self):
        video = [track(source="CAMERA", offset_ms=0, duration_ms=10000)]
        audio = [
            track(source="MICROPHONE", offset_ms=0, duration_ms=10000),
            track(source="SCREEN_SHARE_AUDIO", offset_ms=0, duration_ms=10000),
        ]
        graph = fr.build_filter_complex(video, audio, [], 10000)
        self.assertIn(f"volume={fr.SCREEN_AUDIO_VOLUME}", graph)
        self.assertIn("amix=inputs=2", graph)
        self.assertIn("normalize=0", graph)  # phase-5 교훈: 정규화로 음량이 작아지는 문제 방지
        self.assertIn("alimiter=limit=0.95", graph)

    def test_no_audio_uses_silence(self):
        video = [track(source="CAMERA", offset_ms=0, duration_ms=10000)]
        graph = fr.build_filter_complex(video, [], [], 10000)
        self.assertIn("anullsrc=r=48000:cl=stereo", graph)


if __name__ == "__main__":
    unittest.main(verbosity=2)
