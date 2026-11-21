#!/usr/bin/env bash
# Prints every result in the CodeQL SARIF files given as arguments; exits 1 if there are any.
set -euo pipefail

jq -r '
  .runs[] | (.tool.driver.rules // []) as $rules | .results[]? |
  (.rule.index // -1) as $i | (if $i >= 0 then $rules[$i] else null end) as $rule |
  [
    .ruleId,
    (.level // $rule.defaultConfiguration.level // "warning"),
    ("security-severity " + ($rule.properties["security-severity"] // "n/a")),
    (.locations[0].physicalLocation | "\(.artifactLocation.uri):\(.region.startLine)"),
    .message.text
  ] | join("  ")
' "$@"

count=$(jq '[.runs[].results[]?] | length' "$@" | awk '{s += $1} END {print s + 0}')
echo "CodeQL findings: $count"
[ "$count" -eq 0 ]
