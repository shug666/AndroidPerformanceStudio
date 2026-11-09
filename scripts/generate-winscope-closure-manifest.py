#!/usr/bin/env python3
"""Generates the Winscope viewer closure manifest (SHA-256 per file) and the
third-party license summary consumed at service startup for integrity checks.

Run after replacing the bundled viewer closure (an explicit, manual upgrade per
design D3):  python3 scripts/generate-winscope-closure-manifest.py
"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLOSURE = ROOT / "desktop-viewer/winscope-viewer/winscope-resources/winscope-viewer"
MANIFEST = ROOT / "desktop-viewer/winscope-viewer/winscope-resources/winscope-viewer-manifest.json"

PROVENANCE = {
    "source": "AOSP Winscope production bundle (user-provided dist/prod closure)",
    "aospCommit": "unknown (not embedded in the bundle; confirm on manual upgrade)",
    "buildDateHint": "2026-04-05 (bundle file timestamps)",
    "clientProtocolVersion": "4.0.8",
    "packagingStripped": [
        "winscope_proxy.py (capture owned by APS, not the viewer)",
        "*.bat launch scripts",
        "js/*.LICENSE.txt (collected into THIRD-PARTY-LICENSES.txt instead)",
        "index.html Google Analytics external script tag",
        "index.html remote font @imports (fonts.googleapis.com)",
    ],
}

FORBIDDEN = ("winscope_proxy.py", ".bat", "LICENSE.txt")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    if not CLOSURE.is_dir():
        print(f"closure directory missing: {CLOSURE}", file=sys.stderr)
        return 1
    files = {}
    for path in sorted(CLOSURE.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(CLOSURE).as_posix()
        if relative == MANIFEST.name:
            continue  # the manifest is packaged alongside the closure, never self-tracked
        if any(marker in relative for marker in FORBIDDEN):
            print(f"forbidden file in closure: {relative}", file=sys.stderr)
            return 1
        files[relative] = sha256(path)
    manifest = {
        "provenance": PROVENANCE,
        "files": files,
    }
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"manifest written: {MANIFEST} ({len(files)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
