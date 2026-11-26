#!/usr/bin/env python3
"""spectral/check-example-overridable.py — INV-T-26311 (INV-AC-26309)

Keeps every reason item of every contract example consistent with the error catalogue, the single
source of the flag. Reads `<root>/catalogue/errors/error-codes.yaml` (key `error_codes`, a non-empty
list of mappings, each with a non-empty string `code` and a `disposition`) and every
`<root>/*/openapi.yaml` (one directory level, sorted). For every mapping anywhere in a contract that
has a string `code` and the key `overridable`, the value must be a boolean equal to
(the catalogue disposition of that code == "OVERRIDABLE"), compared case-sensitively and untrimmed.
A code is a code of the catalogue only when it equals a catalogue `code` exactly: `PAY-0011` and
`pay-011` are absent and fail.

Exit 1, with a finding naming the contract, the path and the code, on: a mismatch; a non-boolean
value; a code absent from the catalogue; a mapping with a string `code` and the key `severity` but
no `overridable` that is an item of a `reasons` list inside an example (`example`/`examples`);
an unreadable or malformed catalogue; an unreadable contract; no `*/openapi.yaml` found.
Exit 0 prints `gst-ci: example-overridable OK` with the counts.

Usage: check-example-overridable.py [<root>]      (default root: the current directory)
Standard library plus PyYAML, as the other spectral checks.
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

CAT = "catalogue/errors/error-codes.yaml"


def load_catalogue(root: Path, findings: list[str]) -> dict[str, object] | None:
    path = root / CAT
    if not path.is_file():
        findings.append(f"{CAT}: the catalogue does not exist")
        return None
    try:
        doc = yaml.safe_load(path.read_text())
    except (yaml.YAMLError, OSError, UnicodeDecodeError) as e:
        findings.append(f"{CAT}: the catalogue cannot be parsed: {str(e).splitlines()[0]}")
        return None
    entries = doc.get("error_codes") if isinstance(doc, dict) else None
    if not isinstance(entries, list) or not entries:
        findings.append(f"{CAT}: the key error_codes must be a non-empty list")
        return None
    out: dict[str, object] = {}
    ok = True
    for i, entry in enumerate(entries, 1):
        code = entry.get("code") if isinstance(entry, dict) else None
        if not isinstance(code, str) or not code:
            findings.append(f"{CAT}: entry {i} has no non-empty string code")
            ok = False
            continue
        disp = entry.get("disposition")
        if code in out and out[code] != disp:
            findings.append(f"{CAT}: code {code!r} appears twice with different dispositions")
            ok = False
        out[code] = disp
    return out if ok else None


def walk(node, path: str, contract: str, cat: dict[str, object], findings: list[str],
         count: list[int], in_example: bool, parent_key: str | None) -> None:
    if isinstance(node, dict):
        code = node.get("code")
        if isinstance(code, str):
            if "overridable" in node:
                count[0] += 1
                value = node["overridable"]
                if code not in cat:
                    findings.append(f"{contract}: {path}: code {code!r} is not a code of the catalogue")
                elif not isinstance(value, bool):
                    findings.append(f"{contract}: {path}: code {code!r}: overridable {value!r} is not a boolean")
                elif value != (cat[code] == "OVERRIDABLE"):
                    findings.append(f"{contract}: {path}: code {code!r}: overridable is {str(value).lower()} but the catalogue disposition is {cat[code]!r}")
            elif in_example and parent_key == "reasons" and "severity" in node:
                count[0] += 1
                findings.append(f"{contract}: {path}: code {code!r}: a reason item has a severity but no overridable")
        for k, v in node.items():
            walk(v, f"{path}.{k}" if path else str(k), contract, cat, findings, count,
                 in_example or k in ("example", "examples"), str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, f"{path}[{i}]", contract, cat, findings, count, in_example, parent_key)


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else ".")
    findings: list[str] = []
    cat = load_catalogue(root, findings)
    contracts = sorted(root.glob("*/openapi.yaml"))
    if not contracts:
        findings.append("no */openapi.yaml found")
    count = [0]
    for c in contracts:
        name = c.relative_to(root).as_posix()
        try:
            doc = yaml.safe_load(c.read_text())
        except (yaml.YAMLError, OSError, UnicodeDecodeError) as e:
            findings.append(f"{name}: the contract cannot be parsed: {str(e).splitlines()[0]}")
            continue
        if cat is not None:
            walk(doc, "", name, cat, findings, count, False, None)
    if findings:
        for f in findings:
            print(f"gst-ci: example-overridable FAIL — {f}")
        return 1
    print(f"gst-ci: example-overridable OK — {count[0]} reason item(s) in {len(contracts)} contract(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
