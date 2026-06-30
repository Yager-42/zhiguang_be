from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "skills"))

from skilllib import generate_asset  # noqa: E402


if __name__ == "__main__":
    generate_asset("platform-domain.enum-index")
