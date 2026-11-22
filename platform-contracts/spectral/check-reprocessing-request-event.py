#!/usr/bin/env python3
"""spectral/check-reprocessing-request-event.py — INV-T-26774 (INV-AC-26773; INV-REQ-1006; INV-Q-26498 A).

Fails closed unless, compared by equality after yaml.safe_load / json.load:
 (a) submission/asyncapi.yaml has exactly one channel with the address
     gst.submission.identity-triple-reprocessing-requested.v1, exactly one message named
     com.dynokore.gst.submission.IdentityTripleReprocessingRequested.v1 and exactly one operation
     with action send on that channel;
 (b) submission/schemas/identity-triple-reprocessing-requested-payload.schema.json exists, requires as a set
     entityRef, constitution, effectiveFrom, changeRef, docUuids, and docUuids is an array with
     minItems 1, uniqueItems true, items.format uuid and no maxItems;
 (c) event-log/topics.yaml has exactly one topic with that address, producers [submission-svc] and
     subscribers [canonical-svc].

Prints `reprocessing-request-event: ok` and exits 0, or `reprocessing-request-event FAILED:` and one line per
failed assertion, exit 1. A missing file or key is a finding; an unreadable file exits 1.

Usage: check-reprocessing-request-event.py <root>   (needs python3 and PyYAML)
"""
import json
import sys
from pathlib import Path

import yaml

ADDRESS = "gst.submission.identity-triple-reprocessing-requested.v1"
MESSAGE = "com.dynokore.gst.submission.IdentityTripleReprocessingRequested.v1"
ASYNCAPI = "submission/asyncapi.yaml"
SCHEMA = "submission/schemas/identity-triple-reprocessing-requested-payload.schema.json"
TOPICS = "event-log/topics.yaml"
MEMBERS = {"entityRef", "constitution", "effectiveFrom", "changeRef", "docUuids"}


def load(root: Path, rel: str, loader, errors: list):
    try:
        with open(root / rel, encoding="utf-8") as fh:
            return loader(fh)
    except Exception as ex:  # missing, unreadable or unparsable
        errors.append(f"{rel}: cannot be read: {type(ex).__name__}: {ex}")
        return None


def dicts(value) -> list:
    return [v for v in (value.values() if isinstance(value, dict) else []) if isinstance(v, dict)]


def resolve(doc: dict, node):
    """Follow a local $ref (#/a/b) once; anything else is returned as is."""
    if isinstance(node, dict) and isinstance(node.get("$ref"), str) and node["$ref"].startswith("#/"):
        cur = doc
        for part in node["$ref"][2:].split("/"):
            cur = cur.get(part) if isinstance(cur, dict) else None
        return cur
    return node


def check_asyncapi(doc, errors: list) -> None:
    if not isinstance(doc, dict):
        return
    channels = doc.get("channels")
    chans = {k: v for k, v in (channels.items() if isinstance(channels, dict) else []) if isinstance(v, dict)}
    hits = [k for k, v in chans.items() if v.get("address") == ADDRESS]
    if len(hits) != 1:
        errors.append(f"{ASYNCAPI}: {len(hits)} channels have the address {ADDRESS}, expected exactly one")
    messages = (doc.get("components") or {}).get("messages") if isinstance(doc.get("components"), dict) else None
    named = [m for m in dicts(messages) if m.get("name") == MESSAGE]
    if len(named) != 1:
        errors.append(f"{ASYNCAPI}: {len(named)} messages are named {MESSAGE}, expected exactly one")
    ops = [o for o in dicts(doc.get("operations"))
           if len(hits) == 1 and o.get("action") == "send"
           and isinstance(o.get("channel"), dict) and o["channel"].get("$ref") == f"#/channels/{hits[0]}"]
    if len(ops) != 1:
        errors.append(f"{ASYNCAPI}: {len(ops)} send operations on the channel {ADDRESS}, expected exactly one")


def check_schema(doc, errors: list) -> None:
    if not isinstance(doc, dict):
        return
    req = doc.get("required")
    if not isinstance(req, list) or len(req) != len(set(map(str, req))) or set(req) != MEMBERS:
        errors.append(f"{SCHEMA}: required is {req!r}, expected the set {sorted(MEMBERS)}")
    props = doc.get("properties")
    d = props.get("docUuids") if isinstance(props, dict) else None
    if not isinstance(d, dict):
        errors.append(f"{SCHEMA}: properties.docUuids is missing")
        return
    if d.get("type") != "array":
        errors.append(f"{SCHEMA}: docUuids type is {d.get('type')!r}, expected 'array'")
    if d.get("minItems") != 1 or isinstance(d.get("minItems"), bool):
        errors.append(f"{SCHEMA}: docUuids minItems is {d.get('minItems')!r}, expected 1")
    if d.get("uniqueItems") is not True:
        errors.append(f"{SCHEMA}: docUuids uniqueItems is {d.get('uniqueItems')!r}, expected true")
    items = d.get("items")
    if not isinstance(items, dict) or items.get("format") != "uuid":
        errors.append(f"{SCHEMA}: docUuids items.format is not 'uuid'")
    if "maxItems" in d:
        errors.append(f"{SCHEMA}: docUuids carries maxItems, which the contract must not state")


def check_topics(doc, errors: list) -> None:
    if not isinstance(doc, dict):
        return
    topics = doc.get("topics")
    rows = [t for t in (topics if isinstance(topics, list) else []) if isinstance(t, dict) and t.get("topic") == ADDRESS]
    if len(rows) != 1:
        errors.append(f"{TOPICS}: {len(rows)} topics equal {ADDRESS}, expected exactly one")
        return
    if rows[0].get("producers") != ["submission-svc"]:
        errors.append(f"{TOPICS}: producers of {ADDRESS} are {rows[0].get('producers')!r}, expected ['submission-svc']")
    if rows[0].get("subscribers") != ["canonical-svc"]:
        errors.append(f"{TOPICS}: subscribers of {ADDRESS} are {rows[0].get('subscribers')!r}, expected ['canonical-svc']")


def main(argv: list) -> int:
    if len(argv) != 2:
        print("usage: check-reprocessing-request-event.py <root>", file=sys.stderr)
        return 2
    root = Path(argv[1])
    errors: list = []
    check_asyncapi(load(root, ASYNCAPI, yaml.safe_load, errors), errors)
    check_schema(load(root, SCHEMA, json.load, errors), errors)
    check_topics(load(root, TOPICS, yaml.safe_load, errors), errors)
    if errors:
        print("reprocessing-request-event FAILED:")
        for e in errors:
            print(e)
        return 1
    print("reprocessing-request-event: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
