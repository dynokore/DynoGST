#!/usr/bin/env sh
# spectral/self-test-asyncapi-exceptions.sh — INV-T-23170
# Proves filter-asyncapi-exceptions.py and run-asyncapi-gate.sh fail closed (lesson P20), against fixtures.
# Needs python3 and git. The gate is run with a stub `asyncapi` that prints fixtures/asyncapi-exceptions/breaking.json
# (the real CLI output shape, recorded from the pinned ASYNCAPI_CLI_IMAGE).
set -u
cd "$(dirname "$0")"
failed=0
F=fixtures/asyncapi-exceptions
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
: > "$work/empty.txt"
printf '# Fixture. Owner ruling 2026-11-15.\n{not json\n' > "$work/malformed.txt"
printf '# Fixture. Owner ruling 2026-11-15.\n{"action":"remove","before":{"address":"gst.*"}}\n' > "$work/embedded-glob.txt"
printf '# a comment without any ruling key or date\n%s\n' "$(sed 1d "$F/listed.txt")" > "$work/noruling.txt"

expect() { # <name> <expected-code> <input-file> <exceptions-file>
  python3 -I filter-asyncapi-exceptions.py "$4" < "$3" > "$work/out.txt" 2>&1
  code=$?
  if [ "$code" -ne "$2" ]; then
    echo "gst-ci: FAIL — $1: exit $code, expected $2"; cat "$work/out.txt"; failed=1
  else
    echo "gst-ci: ok — $1 (exit $code)"
  fi
}
echo '[]' > "$work/none.json"
expect "empty list, one breaking entry" 1 "$F/breaking.json" "$work/empty.txt"
grep -q 'rule-pack-activated' "$work/out.txt" || { echo "gst-ci: FAIL — the unlisted entry is not named"; failed=1; }
expect "listed.txt" 0 "$F/breaking.json" "$F/listed.txt"
expect "lookalike.txt" 1 "$F/breaking.json" "$F/lookalike.txt"
expect "uncommented.txt" 1 "$F/breaking.json" "$F/uncommented.txt"
expect "glob.txt" 1 "$F/breaking.json" "$F/glob.txt"
expect "embedded wildcard" 1 "$F/breaking.json" "$work/embedded-glob.txt"
expect "malformed line" 1 "$F/breaking.json" "$work/malformed.txt"
expect "comment without ruling" 1 "$F/breaking.json" "$work/noruling.txt"
expect "[] with empty list" 0 "$work/none.json" "$work/empty.txt"
expect "the committed list accepts an empty breaking list" 0 "$work/none.json" asyncapi-exceptions.txt
# Each committed entry is one JSON object line directly below a "# " comment line (INV-T-23124 adds the first entries).
if ! awk 'prev !~ /^# / && $0 !~ /^#/ && $0 != "" {bad=1} {prev=$0} END {exit bad}' asyncapi-exceptions.txt; then echo "gst-ci: FAIL — asyncapi-exceptions.txt has an entry without a comment line above it"; failed=1; fi
if grep -v '^#' asyncapi-exceptions.txt | grep -v '^{' | grep -q .; then echo "gst-ci: FAIL — asyncapi-exceptions.txt has a line that is neither a comment nor a JSON object"; failed=1; fi
printf 'not json' > "$work/bad.json"
expect "input that is not JSON" 1 "$work/bad.json" "$work/empty.txt"

# INV-T-28987: only a forward MINOR or PATCH step of /info/version passes without an exception entry.
step() { # <name> <expected-code> <json-text>
  printf '%s' "$3" > "$work/step.json"
  expect "$1" "$2" "$work/step.json" "$work/empty.txt"
}
el() { # <before-json> <after-json> [<path> [<action> [<type>]]]
  printf '{"action":"%s","path":"%s","before":%s,"after":%s,"type":"%s"}' "${4:-edit}" "${3:-/info/version}" "$1" "$2" "${5:-breaking}"
}
step "forward minor step" 0 "[$(el '"1.4.0"' '"1.5.0"')]"
step "forward patch step" 0 "[$(el '"1.4.0"' '"1.4.1"')]"
step "forward minor step, compared as integers" 0 "[$(el '"1.9.3"' '"1.10.0"')]"
step "forward patch step, compared as integers" 0 "[$(el '"1.4.9"' '"1.4.10"')]"
step "downgrade" 1 "[$(el '"1.5.0"' '"1.4.0"')]"
step "downgrade, compared as integers" 1 "[$(el '"1.10.0"' '"1.9.0"')]"
step "patch downgrade" 1 "[$(el '"1.4.1"' '"1.4.0"')]"
step "equal versions" 1 "[$(el '"1.4.0"' '"1.4.0"')]"
step "major step" 1 "[$(el '"1.4.0"' '"2.0.0"')]"
step "major step with greater minor" 1 "[$(el '"1.4.0"' '"2.5.0"')]"
step "before 1.4" 1 "[$(el '"1.4"' '"1.5.0"')]"
step "after 1.05.0" 1 "[$(el '"1.4.0"' '"1.05.0"')]"
step "after 1.5.0-rc.1" 1 "[$(el '"1.4.0"' '"1.5.0-rc.1"')]"
step "after 1.5.0+build" 1 "[$(el '"1.4.0"' '"1.5.0+build"')]"
step "after 1.5.0.1" 1 "[$(el '"1.4.0"' '"1.5.0.1"')]"
step "after with a trailing newline" 1 "[$(el '"1.4.0"' '"1.5.0\n"')]"
step "after with a non-ASCII digit" 1 "[$(el '"1.4.0"' '"1.\u0665.0"')]"
step "after a number instead of a string" 1 "[$(el '"1.4.0"' '1.5')]"
step "before a number instead of a string" 1 "[$(el '1.4' '"1.5.0"')]"
step "path /info/title" 1 "[$(el '"1.4.0"' '"1.5.0"' /info/title)]"
step "path /channels/x/info/version" 1 "[$(el '"1.4.0"' '"1.5.0"' /channels/x/info/version)]"
step "path /info/version/x" 1 "[$(el '"1.4.0"' '"1.5.0"' /info/version/x)]"
step "action remove on /info/version" 1 "[$(el '"1.4.0"' '"1.5.0"' /info/version remove)]"
step "action Edit" 1 "[$(el '"1.4.0"' '"1.5.0"' /info/version Edit)]"
step "type unclassified" 1 "[$(el '"1.4.0"' '"1.5.0"' /info/version edit unclassified)]"
step "an element with one extra key" 1 '[{"action":"edit","path":"/info/version","before":"1.4.0","after":"1.5.0","type":"breaking","extra":1}]'
step "an element without type" 1 '[{"action":"edit","path":"/info/version","before":"1.4.0","after":"1.5.0"}]'
step "an element that is not an object" 1 '["/info/version"]'
step "an object instead of an array" 1 "$(el '"1.4.0"' '"1.5.0"')"
step "empty input" 1 ''
step "forward step with one removal" 1 "[$(el '"1.4.0"' '"1.5.0"'),{\"action\":\"remove\",\"path\":\"/channels/gone\",\"before\":{},\"type\":\"breaking\"}]"
listed="$(grep 'unlisted breaking AsyncAPI change:' "$work/out.txt")"
if [ "$listed" != 'gst-ci: unlisted breaking AsyncAPI change: {"action":"remove","before":{},"path":"/channels/gone","type":"breaking"}' ]; then
  echo "gst-ci: FAIL — forward step with one removal: not only the removal is listed as unlisted"; cat "$work/out.txt"; failed=1
fi

# Gate: a repository with one service whose channel is removed at head.
mkdir "$work/bin" "$work/repo"
printf '#!/bin/sh\ncat "%s/%s/breaking.json"\nexit 1\n' "$PWD" "$F" > "$work/bin/asyncapi"
chmod +x "$work/bin/asyncapi"
here="$PWD"
(
  cd "$work/repo" && git init -q . && mkdir svc && echo 'asyncapi: 3.0.0' > svc/asyncapi.yaml &&
  git add . && git -c user.name=t -c user.email=t@t commit -qm base
) || { echo "gst-ci: FAIL — fixture repository"; exit 1; }
base="$(git -C "$work/repo" rev-parse HEAD)"
gate() { # <name> <expected-code> <exceptions-file>
  (cd "$work/repo" && PATH="$work/bin:$PATH" sh "$here/run-asyncapi-gate.sh" "$base" "$3") > "$work/out.txt" 2>&1
  code=$?
  if [ "$code" -ne "$2" ]; then echo "gst-ci: FAIL — gate $1: exit $code, expected $2"; cat "$work/out.txt"; failed=1
  else echo "gst-ci: ok — gate $1 (exit $code)"; fi
}
gate "removed channel, empty exception file" 1 "$work/empty.txt"
gate "removed channel, listed.txt" 0 "$here/$F/listed.txt"
gate "removed channel, lookalike.txt" 1 "$here/$F/lookalike.txt"
if grep -q '"\$breaking" != "\[\]"' run-asyncapi-gate.sh; then echo "gst-ci: FAIL — the gate still compares with []"; failed=1; fi
grep -q 'filter-asyncapi-exceptions.py' run-asyncapi-gate.sh || { echo "gst-ci: FAIL — the gate does not use the filter"; failed=1; }
exit $failed
