#!/usr/bin/env sh
# spectral/self-test-asyncapi-gate-cli.sh — INV-T-28987
# Runs run-asyncapi-gate.sh with the REAL AsyncAPI CLI (the pinned ASYNCAPI_CLI_IMAGE) on a fixture repository whose
# svc/asyncapi.yaml references one file below svc/ and one file in a sibling directory by relative $ref, with an
# empty exception file. Each case compares the exit code of the gate with the expected code by equality.
# Needs asyncapi, python3 and git.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
failed=0
work="$(mktemp -d)" || exit 1
trap 'rm -rf "$work"' EXIT
repo="$work/repo"
: > "$work/empty.txt"

write_document() { # <version> [without-second-channel]
  {
    cat <<YAML
asyncapi: 3.0.0
info:
  title: gate fixture
  version: $1
channels:
  keptChannel:
    address: gst.svc.kept.v1
    messages:
      Below:
        \$ref: './messages/below.yaml'
YAML
    if [ "${2:-}" != "without-second-channel" ]; then
      cat <<YAML
  removedChannel:
    address: gst.svc.removed.v1
    messages:
      Sibling:
        \$ref: '../shared/sibling.yaml'
YAML
    else
      cat <<YAML
      Sibling:
        \$ref: '../shared/sibling.yaml'
YAML
    fi
  } > "$repo/svc/asyncapi.yaml"
}

write_message() { # <file> <name>
  cat > "$1" <<YAML
name: $2
payload:
  type: object
  properties:
    id:
      type: string
YAML
}

mkdir -p "$repo/svc/messages" "$repo/shared" || exit 1
write_document 1.4.0
write_message "$repo/svc/messages/below.yaml" Below
write_message "$repo/shared/sibling.yaml" Sibling
(
  cd "$repo" && git init -q . && git add . && git -c user.name=t -c user.email=t@t commit -qm base
) || { echo "gst-ci: FAIL — fixture repository"; exit 1; }
base="$(git -C "$repo" rev-parse HEAD)" || exit 1

restore() { # puts the working tree (the gate's head) back to the base commit
  (cd "$repo" && git checkout -q "$base" -- . && git clean -qfdx) || { echo "gst-ci: FAIL — fixture restore"; exit 1; }
}

gate() { # <name> <expected-code> [<base-sha>]
  (cd "$repo" && sh "$here/run-asyncapi-gate.sh" "${3:-$base}" "$work/empty.txt") > "$work/out.txt" 2>&1
  code=$?
  if [ "$code" -ne "$2" ]; then echo "gst-ci: FAIL — real CLI, $1: exit $code, expected $2"; cat "$work/out.txt"; failed=1
  else echo "gst-ci: ok — real CLI, $1 (exit $code)"; fi
}

finding() { # <name> <path>: the filter lists a finding with exactly this path
  if ! grep 'unlisted breaking AsyncAPI change:' "$work/out.txt" | grep -qF "\"path\":\"$2\""; then
    echo "gst-ci: FAIL — real CLI, $1: no finding names $2"; cat "$work/out.txt"; failed=1
  fi
}

gate "head equal to base" 0

restore; write_document 1.5.0
gate "only info.version stepped by a minor" 0

restore; write_document 1.4.1
gate "only info.version stepped by a patch" 0

restore; write_document 1.4.0 without-second-channel
gate "a removed channel" 1
finding "a removed channel" /channels/removedChannel

restore; write_document 1.5.0 without-second-channel
gate "a removed channel with a minor step" 1
finding "a removed channel with a minor step" /channels/removedChannel

restore; write_document 2.0.0
gate "a major step" 1
finding "a major step" /info/version

restore; write_document 1.3.0
gate "a version downgrade" 1
finding "a version downgrade" /info/version

restore; printf 'asyncapi: 3.0.0\nchannels: 5\n' > "$repo/svc/asyncapi.yaml"
gate "an invalid head document" 1

restore; rm "$repo/svc/asyncapi.yaml"
gate "a document present at base and absent at head" 1

restore
gate "a base commit id that does not exist" 1 0000000000000000000000000000000000000000

restore; rm "$repo/shared/sibling.yaml"
gate "a referenced sibling file removed at head" 1

restore; rm "$repo/svc/messages/below.yaml"
gate "a referenced file below the document removed at head" 1

restore
(
  cd "$repo" && git rm -q svc/asyncapi.yaml && git -c user.name=t -c user.email=t@t commit -qm no-document
) || { echo "gst-ci: FAIL — fixture commit without a document"; exit 1; }
gate "a base tree without any document" 1 "$(git -C "$repo" rev-parse HEAD)"

exit $failed
