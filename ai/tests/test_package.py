from __future__ import annotations

import subprocess
import sys


def test_package_exposes_version() -> None:
    import zani_ai

    assert zani_ai.__version__ == "0.1.0"


def test_module_entrypoint_reports_project_and_python_version() -> None:
    result = subprocess.run(
        [sys.executable, "-m", "zani_ai"],
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "zani-ai 0.1.0" in result.stdout
    assert "Python 3.12" in result.stdout
