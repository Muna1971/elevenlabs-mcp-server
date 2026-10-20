#!/usr/bin/env python3
"""Builds app/src/main/assets/bergamot_models.json from Mozilla's translation model registry.

Keeps only what the app needs: every "<lang>-en" pair (to pivot through English)
plus "en-ar". For each pair the most production-ready model is picked.

Usage: python3 tools/make_bergamot_catalog.py [models.json]
"""
import json
import sys
import urllib.request
from pathlib import Path

REGISTRY = ("https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data"
            "/db/models.json")
STATUS_RANK = {"Release": 0, "Release Android": 1, "Release Desktop": 2, None: 3, "Nightly": 4}
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/bergamot_models.json"


def main() -> None:
    if len(sys.argv) > 1:
        registry = json.loads(Path(sys.argv[1]).read_text())
    else:
        registry = json.load(urllib.request.urlopen(REGISTRY))

    pairs = {}
    for pair, entries in sorted(registry["models"].items()):
        src, _, tgt = pair.partition("-")
        if not (tgt == "en" or pair == "en-ar"):
            continue
        best = min(entries, key=lambda e: STATUS_RANK.get(e.get("releaseStatus"), 5))
        files = best["files"]
        pairs[pair] = {
            "model": files["model"]["path"],
            "lex": files["lexicalShortlist"]["path"],
            "vocab": files.get("vocab", files.get("srcVocab"))["path"],
            **({"trgVocab": files["trgVocab"]["path"]} if "trgVocab" in files else {}),
        }

    OUT.write_text(json.dumps({"baseUrl": registry["baseUrl"], "pairs": pairs}, indent=1) + "\n")
    print(f"wrote {len(pairs)} pairs to {OUT}")


if __name__ == "__main__":
    main()
