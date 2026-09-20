# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""One-shot, non-overwriting import of the pinned Baritone movement/search sources.

Version adaptations live in the imported files and baritone.compat. This script
records provenance, not a code generator used during the build.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REVISION = "d9cb2d91a06501c5bcba2181509d0df80361f413"
DEST = ROOT / "gtnh/baritone/src/upstream/java"
TREES = [
    "src/main/java/baritone/pathing/calc",
    "src/main/java/baritone/pathing/movement",
    "src/main/java/baritone/pathing/path",
    "src/main/java/baritone/pathing/precompute",
    "src/api/java/baritone/api/pathing",
]
FILES = [
    "src/api/java/baritone/api/utils/" + name + ".java" for name in [
        "BetterBlockPos", "Rotation", "RotationUtils", "VecUtils", "RayTraceUtils",
        "PathCalculationResult", "TypeUtils", "input/Input", "interfaces/IGoalRenderPos",
    ]
] + [
    "src/main/java/baritone/utils/pathing/MutableMoveResult.java",
    "src/main/java/baritone/utils/PathingCommandContext.java",
]
REPLACEMENTS = {
    "net.minecraft.util.math.": "baritone.compat.",
    "net.minecraft.util.EnumFacing": "baritone.compat.EnumFacing",
    "net.minecraft.block.state.IBlockState": "baritone.compat.IBlockState",
    "net.minecraft.util.Tuple": "baritone.compat.Tuple",
    "net.minecraft.util.EnumHand": "baritone.compat.EnumHand",
    "net.minecraft.util.EnumActionResult": "baritone.compat.EnumActionResult",
}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify',action='store_true',help='verify every pinned source hash and the complete imported-file inventory')
    parser.add_argument('--add',nargs='+',help='append explicitly selected pinned source files without overwriting existing ports')
    args=parser.parse_args()
    if args.verify:
        manifest=json.loads((DEST.parents[2]/'UPSTREAM_SOURCES.json').read_text(encoding='utf-8'))
        assert manifest['revision']==REVISION,'unexpected upstream baseline'
        expected=set()
        for row in manifest['files']:
            expected.add(row['ported'])
            original=subprocess.check_output(['git','show',f"{REVISION}:{row['source']}"],cwd=ROOT)
            assert hashlib.sha256(original).hexdigest()==row['sourceSha256'],row['source']
            target=DEST/row['ported']
            assert target.is_file() and 'GNU Lesser General Public License' in target.read_text(encoding='utf-8'),row['ported']
        actual={p.relative_to(DEST).as_posix() for p in DEST.rglob('*.java')}
        assert actual==expected,{'unrecorded':sorted(actual-expected),'missing':sorted(expected-actual)}
        print(f'Verified {len(expected)} imported files and pinned upstream hashes')
        return
    paths = args.add or subprocess.check_output(
        ["git", "ls-tree", "-r", "--name-only", REVISION, *TREES], cwd=ROOT, text=True
    ).splitlines() + FILES
    manifest_file=DEST.parents[2]/'UPSTREAM_SOURCES.json'
    manifest = json.loads(manifest_file.read_text(encoding='utf-8'))['files'] if args.add else []
    for path in sorted(set(paths)):
        if not path.endswith(".java"):
            continue
        data = subprocess.check_output(["git", "show", f"{REVISION}:{path}"], cwd=ROOT)
        relative = path.split("/java/", 1)[1]
        target = DEST / relative
        if target.exists():
            raise SystemExit(f"Refusing to overwrite ported file: {target}")
        text = data.decode("utf-8")
        for old, new in REPLACEMENTS.items():
            text = text.replace(old, new)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8", newline="\n")
        manifest.append({"source": path, "ported": relative, "sourceSha256": hashlib.sha256(data).hexdigest()})
    manifest_file.write_text(
        json.dumps({"revision": REVISION, "files": manifest}, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Imported {len(manifest)} pinned sources without overwriting existing files")

if __name__ == "__main__":
    main()
