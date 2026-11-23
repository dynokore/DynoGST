#!/usr/bin/env sh
# spectral/self-test-spec-facts.sh — INV-T-9614 (lesson P20): proves check-spec-facts.py fires. One passing root
# and one root for each failure of the table check (fixtures/spec-facts/<case>/), then the --base ratchet on a
# throwaway git repository built here and removed at the end.
set -u
cd "$(dirname "$0")"
F=fixtures/spec-facts
failed=0
out=$(python3 check-spec-facts.py $F/pass 2>&1) || { echo "gst-ci: FAIL — compliant fixture rejected:"; echo "$out"; failed=1; }
expect_fail() { # case, finding text
  out=$(python3 check-spec-facts.py "$F/$1" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then echo "gst-ci: FAIL — '$1' fixture was accepted"; failed=1
  else case "$out" in *"$2"*) echo "gst-ci: self-test '$1' failed as expected ($2)";;
       *) echo "gst-ci: FAIL — '$1' failed, but not with '$2':"; echo "$out"; failed=1;; esac; fi
}
expect_fail no-table "no */spec-facts.tsv found"
expect_fail empty-table "the table has no rows"
expect_fail four-cells "five non-empty cells"
expect_fail six-cells "five non-empty cells"
expect_fail relation-Equals "is not one of equals, contains, absent"
expect_fail relation-equal "is not one of equals, contains, absent"
expect_fail value-not-json "is not valid JSON"
expect_fail absent-with-value "must be '-'"
expect_fail missing-file "does not exist"
expect_fail dir-name-repeated "does not exist"
expect_fail path-leaves-dir "leaves the directory"
expect_fail unparseable-file "cannot be parsed"
expect_fail pointer-no-slash "does not start with '/'"
expect_fail pointer-bad-escape "bad escape"
expect_fail pointer-other-case "the node is missing"
expect_fail missing-node "the node is missing"
expect_fail absent-present "but the row says absent"
expect_fail string-vs-number "the row says 60"
expect_fail number-vs-string "the row says \"60\""
expect_fail one-vs-true "the row says true"
expect_fail true-vs-one "the row says 1"
expect_fail list-order "the row says [\"B\",\"A\"]"
expect_fail contains-not-list "is not a list"
expect_fail contains-missing-member "has no member"
expect_fail duplicate-row "duplicate row"
expect_fail other-service-only "does not exist"
# the finding names the table, the row number, the pointer and the requirement key
out=$(python3 check-spec-facts.py $F/one-vs-true 2>&1)
case "$out" in *"spec-facts.tsv: row 2, pointer /a/one, requirement INV-REQ-1"*) ;; *) echo "gst-ci: FAIL — finding does not name table, row, pointer and requirement:"; echo "$out"; failed=1;; esac

# the ratchet: a throwaway repository with one committed table
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R="$T/repo"; mkdir -p "$R/svc"
cp $F/pass/svc/doc.yaml $F/pass/svc/spec-facts.tsv "$R/svc/"
git -C "$R" init -q && git -C "$R" config user.email t@t && git -C "$R" config user.name t
git -C "$R" add -A && git -C "$R" commit -q -m base
BASE=$(git -C "$R" rev-parse HEAD)
expect_base() { # name, expected rc, finding text (or empty), sha
  out=$(python3 check-spec-facts.py --base "$4" "$R" 2>&1); rc=$?
  if [ "$rc" != "$2" ]; then echo "gst-ci: FAIL — base case '$1' exited $rc, expected $2:"; echo "$out"; failed=1
  elif [ -n "$3" ]; then case "$out" in *"$3"*) echo "gst-ci: self-test base '$1' failed as expected ($3)";; *) echo "gst-ci: FAIL — base case '$1' lacks '$3':"; echo "$out"; failed=1;; esac
  else echo "gst-ci: self-test base '$1' passed as expected"; fi
}
expect_base unchanged 0 "" "$BASE"
# a changed requirement cell, an added row and the header are not findings
sed 's/INV-REQ-1$/INV-REQ-9/' "$R/svc/spec-facts.tsv" > "$T/t" && cp "$T/t" "$R/svc/spec-facts.tsv"
printf 'doc.yaml\t/a/one\tequals\t1\tINV-REQ-2\n' >> "$R/svc/spec-facts.tsv"
expect_base requirement-changed-row-added 0 "" "$BASE"
git -C "$R" checkout -q -- svc/spec-facts.tsv
grep -v '/a/n' "$R/svc/spec-facts.tsv" > "$T/t" && cp "$T/t" "$R/svc/spec-facts.tsv"
expect_base removed-row 1 "row removed since" "$BASE"
git -C "$R" checkout -q -- svc/spec-facts.tsv
sed 's#/a/n\tequals\t60#/a/n\tequals\t61#' "$R/svc/spec-facts.tsv" > "$T/t" && cp "$T/t" "$R/svc/spec-facts.tsv"
sed -i 's/^doc.yaml\t\/a\/n\tequals\t60\t/doc.yaml\t\/a\/n\tequals\t60.0\t/' "$R/svc/spec-facts.tsv"
expect_base changed-value 1 "row removed since" "$BASE"
git -C "$R" checkout -q -- svc/spec-facts.tsv
mv "$R/svc/spec-facts.tsv" "$R/svc/spec-fact.tsv"
expect_base table-missing 1 "is missing at the head" "$BASE"
mv "$R/svc/spec-fact.tsv" "$R/svc/spec-facts.tsv"
expect_base empty-sha 1 "needs a commit sha" ""
expect_base dash-sha 1 "needs a commit sha" "--x"
expect_base not-a-commit 1 "is not a commit" "0000000000000000000000000000000000000000"

# the list rule (INV-T-28239): an equals list row is kept by an equals list row of the same file and pointer that
# holds every member; every other row is kept only by its exact key. Own repository, own doc.yaml and table.
LR="$T/lrepo"; mkdir -p "$LR/svc"
git -C "$LR" init -q && git -C "$LR" config user.email t@t && git -C "$LR" config user.name t
printf 'a:\n  list: [A, B]\n  typed: [1, "60"]\n  n: 60\n' > "$LR/svc/doc.yaml"
TAB=$(printf '\t')
HDR=$(printf 'file\tpointer\trelation\tvalue\trequirement')
L1='doc.yaml\t/a/list\tequals\t["A","B"]\tINV-REQ-1'
T1='doc.yaml\t/a/typed\tequals\t[1,"60"]\tINV-REQ-1'
S1='doc.yaml\t/a/n\tequals\t60\tINV-REQ-1'
C1='doc.yaml\t/a/list\tcontains\t"A"\tINV-REQ-1'
table() { { echo "$HDR"; for r in "$@"; do printf "$r\n"; done; } > "$LR/svc/spec-facts.tsv"; }
table "$L1" "$T1" "$S1" "$C1"
git -C "$LR" add -A && git -C "$LR" commit -q -m base
LBASE=$(git -C "$LR" rev-parse HEAD)
ROWMSG='row removed since'
# lcase name rc text doc-list typed-list n rows...   (rewrites doc.yaml so that the table check of the head passes)
lcase() {
  name=$1; rc=$2; text=$3; list=$4; typed=$5; n=$6; shift 6
  printf 'a:\n  list: %s\n  typed: %s\n  n: %s\n  other: %s\n' "$list" "$typed" "$n" "$list" > "$LR/svc/doc.yaml"
  printf 'a:\n  list: %s\n' "$list" > "$LR/svc/doc2.yaml"
  table "$@"
  out=$(python3 check-spec-facts.py --base "$LBASE" "$LR" 2>&1); got=$?
  if [ "$got" != "$rc" ]; then echo "gst-ci: FAIL — list case '$name' exited $got, expected $rc:"; echo "$out"; failed=1
  elif [ -n "$text" ]; then
    case "$out" in *"$text"*"an equals list row is kept only by"*) echo "gst-ci: self-test list '$name' failed as expected ($text)";; *) echo "gst-ci: FAIL — list case '$name' lacks '$text':"; echo "$out"; failed=1;; esac
  else case "$out" in *"$ROWMSG"*) echo "gst-ci: FAIL — list case '$name' reported a removed row:"; echo "$out"; failed=1;; *) echo "gst-ci: self-test list '$name' passed as expected";; esac; fi
  git -C "$LR" checkout -q -- . ; rm -f "$LR/svc/doc2.yaml"
}
LTXT="$ROWMSG $LBASE: file doc.yaml, pointer /a/list, relation equals, value [\"A\",\"B\"]"
TTXT="$ROWMSG $LBASE: file doc.yaml, pointer /a/typed, relation equals, value [1,\"60\"]"
STXT="$ROWMSG $LBASE: file doc.yaml, pointer /a/n, relation equals, value 60"
CTXT="$ROWMSG $LBASE: file doc.yaml, pointer /a/list, relation contains, value \"A\""
L() { printf 'doc.yaml\t/a/list\tequals\t%s\tINV-REQ-1' "$1"; }
lcase list-appended 0 "" '[A, B, C]' '[1, "60"]' 60 "$(L '["A","B","C"]')" "$T1" "$S1" "$C1"
lcase list-reordered 0 "" '[B, A]' '[1, "60"]' 60 "$(L '["B","A"]')" "$T1" "$S1" "$C1"
lcase list-appended-reordered 0 "" '[C, B, A]' '[1, "60"]' 60 "$(L '["C","B","A"]')" "$T1" "$S1" "$C1"
lcase list-requirement-changed-member-added 0 "" '[A, B, C]' '[1, "60"]' 60 'doc.yaml\t/a/list\tequals\t["A","B","C"]\tINV-REQ-9' "$T1" "$S1" "$C1"
lcase list-member-removed 1 "$LTXT" '[A]' '[1, "60"]' 60 "$(L '["A"]')" "$T1" "$S1" "$C1"
lcase list-member-replaced 1 "$LTXT" '[A, C]' '[1, "60"]' 60 "$(L '["A","C"]')" "$T1" "$S1" "$C1"
lcase typed-one-to-true 1 "$TTXT" '[A, B]' '[true, "60"]' 60 "$L1" 'doc.yaml\t/a/typed\tequals\t[true,"60"]\tINV-REQ-1' "$S1" "$C1"
lcase typed-string-to-number 1 "$TTXT" '[A, B]' '[1, 60]' 60 "$L1" 'doc.yaml\t/a/typed\tequals\t[1,60]\tINV-REQ-1' "$S1" "$C1"
lcase list-to-contains 1 "$LTXT" '[A, B]' '[1, "60"]' 60 'doc.yaml\t/a/list\tcontains\t"A"\tINV-REQ-1' "$T1" "$S1"
lcase list-other-pointer 1 "$LTXT" '[A, B]' '[1, "60"]' 60 'doc.yaml\t/a/other\tequals\t["A","B"]\tINV-REQ-1' "$T1" "$S1" "$C1"
lcase list-other-file 1 "$LTXT" '[A, B]' '[1, "60"]' 60 'doc2.yaml\t/a/list\tequals\t["A","B"]\tINV-REQ-1' "$T1" "$S1" "$C1"
lcase list-to-scalar 1 "$LTXT" 'A' '[1, "60"]' 60 'doc.yaml\t/a/list\tequals\t"A"\tINV-REQ-1' "$T1" "$S1" "$C1"
lcase scalar-changed 1 "$STXT" '[A, B]' '[1, "60"]' 61 "$L1" "$T1" 'doc.yaml\t/a/n\tequals\t61\tINV-REQ-1' "$C1"
lcase scalar-to-list 1 "$STXT" '[A, B]' '[1, "60"]' '[60]' "$L1" "$T1" 'doc.yaml\t/a/n\tequals\t[60]\tINV-REQ-1' "$C1"
lcase contains-removed 1 "$CTXT" '[A, B]' '[1, "60"]' 60 "$L1" "$T1" "$S1"
# an empty base list is kept by any list (vacuous truth)
printf 'a:\n  list: []\n' > "$LR/svc/doc.yaml"; table "$(L '[]')"
git -C "$LR" add -A && git -C "$LR" commit -q -m empty-list
LBASE=$(git -C "$LR" rev-parse HEAD)
printf 'a:\n  list: [A]\n' > "$LR/svc/doc.yaml"; table "$(L '["A"]')"
expect_list_empty() { out=$(python3 check-spec-facts.py --base "$LBASE" "$LR" 2>&1) && echo "gst-ci: self-test list 'empty-base-list' passed as expected" || { echo "gst-ci: FAIL — empty base list not kept:"; echo "$out"; failed=1; }; }
expect_list_empty
git -C "$LR" checkout -q -- .
[ $failed -eq 0 ] && echo "gst-ci: spec-facts self-test OK"
exit $failed
