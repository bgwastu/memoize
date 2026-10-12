"""Regenerate Memos' Unicode 17 tag data: python3 Scripts/update-tag-data.py /path/to/memos"""
import ast
import html.entities
import json
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]) / "markdown/parser/tag_unicode_tables.go"
text = source.read_text()
data = {}
for key in ("XIDContinue", "DefaultIgnorable", "CombiningMarks"):
    body = text.split("var unicode17" + key + " =", 1)[1].split("\n}", 1)[0]
    data[key] = [[int(a, 16), int(b, 16)] for a, b in re.findall(r"lo: 0x([0-9A-F]+), hi: 0x([0-9A-F]+)", body)]
body = text.split("var emoji17FullyQualified =", 1)[1]
data["emoji"] = [ast.literal_eval(s) for s in re.findall(r'("(?:[^"\\]|\\.)*")\s*:', body)]
data["entities"] = sorted({key.removesuffix(";") for key in html.entities.html5})
data["entityValues"] = {key.removesuffix(";"): value for key, value in html.entities.html5.items() if key.endswith(";")}
target = Path(__file__).resolve().parents[2] / "shared/assets/TagData.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")))
native = Path(__file__).resolve().parents[1] / "Sources/MemoizeCore/Resources/TagData.json"
native.write_bytes(target.read_bytes())
print(f"Generated {len(data['emoji'])} emoji sequences and Unicode ranges ({target.stat().st_size} bytes).")
