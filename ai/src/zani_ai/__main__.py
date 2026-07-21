from __future__ import annotations

import sys

from zani_ai import __version__


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "engagement":
        from zani_ai.engagement.cli import main as engagement_main

        return engagement_main(sys.argv[2:])
    python_version = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    print(f"zani-ai {__version__} | Python {python_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
