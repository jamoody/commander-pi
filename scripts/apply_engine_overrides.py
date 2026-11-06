"""Apply reviewed source fixes to the pinned Forge checkout, refusing unknown files."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def digest(path):
    # Git checkouts may normalize line endings on Windows.
    return hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--forge", type=Path, required=True)
    args = parser.parse_args()
    root = args.forge.resolve()
    overrides = Path(__file__).resolve().parents[1] / "engine-overrides"
    manifest = json.loads((overrides / "manifest.json").read_text())
    pending = []
    for relative, hashes in manifest["files"].items():
        source = overrides / relative
        target = root / relative
        if digest(source) != hashes["replacementSha256"]:
            raise SystemExit(f"Override checksum mismatch: {source}")
        if hashes["originalSha256"] is None and not target.exists():
            pending.append((source, target))
            continue
        if not target.is_file():
            raise SystemExit(f"Missing Forge source: {target}")
        current = digest(target)
        if current == hashes["replacementSha256"]:
            continue
        if current != hashes["originalSha256"]:
            raise SystemExit(f"Refusing to overwrite unexpected source: {target}")
        pending.append((source, target))
    for source, target in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    print(f"Applied {len(pending)} engine override(s); all overrides verified.")


if __name__ == "__main__":
    main()
