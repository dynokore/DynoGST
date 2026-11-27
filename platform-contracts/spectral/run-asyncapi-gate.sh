#!/usr/bin/env sh
# spectral/run-asyncapi-gate.sh — INV-T-2849 (T5), API-03, API-41
#
# For every directory's asyncapi.yaml that already existed at the merge
# request's base commit, fails on a breaking structural change (a removed
# channel/operation/message, a changed address, …) via the AsyncAPI CLI's own
# `diff -t breaking`. Payload-level compatibility is a separate, additive-only
# check (run-oasdiff-gate.sh's check-payload-compat.py) — the AsyncAPI CLI does
# not itself deeply diff payload schemas.
#
# The whole tree of the base commit is materialised in a directory of its own, so that relative $ref values of a
# base document resolve at the base (INV-T-28987). The documents compared are the <dir>/asyncapi.yaml files of that
# tree, except under spectral/. Fail closed: a base commit that cannot be archived or extracted, a base tree without
# such a document, and a document that is absent at head fail the gate. A document new at head has nothing to compare.
# The standard output of the CLI goes to the filter every time, also when it is empty; the exit status of the CLI
# alone decides nothing, because it is 1 both for findings and for an invalid document.
#
# A breaking change passes only when it is a forward MINOR or PATCH step of /info/version, or its whole element equals
# a commented entry of spectral/asyncapi-exceptions.txt (spectral/filter-asyncapi-exceptions.py; INV-T-23170).
#
# Usage (from the repository root): run-asyncapi-gate.sh <base-sha> [<exceptions-file>]
set -u
base_sha="$1"
here="$(cd "$(dirname "$0")" && pwd)"
exceptions="${2:-$here/asyncapi-exceptions.txt}"
failed=0

work="$(mktemp -d)" || exit 1
trap 'rm -rf "$work"' EXIT
base_tree="$work/base"
mkdir "$base_tree" || exit 1
if ! git archive --format=tar -o "$work/base.tar" "$base_sha^{commit}" || ! tar -xf "$work/base.tar" -C "$base_tree"; then
  echo "gst-ci: cannot materialise the tree of base commit $base_sha"
  exit 1
fi

(cd "$base_tree" && find . -mindepth 2 -maxdepth 2 -name asyncapi.yaml ! -path ./spectral/asyncapi.yaml) > "$work/found" || exit 1
sed 's|^\./||' "$work/found" | LC_ALL=C sort > "$work/documents" || exit 1
if [ ! -s "$work/documents" ]; then
  echo "gst-ci: base commit $base_sha holds no <dir>/asyncapi.yaml to compare"
  exit 1
fi

# Standard output of a comparison must carry only the result. The output and exit status of this command decide nothing.
asyncapi config analytics --disable < /dev/null > /dev/null 2>&1

while IFS= read -r doc; do
  if [ ! -f "$doc" ]; then
    echo "gst-ci: $doc exists at base $base_sha and is absent at head"
    failed=1
    continue
  fi
  echo "gst-ci: asyncapi diff $doc against $base_sha"
  asyncapi diff "$base_tree/$doc" "$doc" -t breaking -f json < /dev/null > "$work/breaking.json" 2> "$work/stderr.txt"
  if ! python3 "$here/filter-asyncapi-exceptions.py" "$exceptions" < "$work/breaking.json"; then
    echo "gst-ci: breaking AsyncAPI structural change or failed comparison in $doc:"
    cat "$work/breaking.json"
    echo "gst-ci: standard error of the AsyncAPI CLI:"
    cat "$work/stderr.txt"
    failed=1
  fi
done < "$work/documents"

exit $failed
