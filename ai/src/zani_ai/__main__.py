from __future__ import annotations

import sys

from zani_ai import __version__


def main() -> int:
    python_version = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    print(f"zani-ai {__version__} | Python {python_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
