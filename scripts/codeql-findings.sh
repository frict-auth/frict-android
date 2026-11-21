#!/usr/bin/env bash
# Prints every result in the CodeQL SARIF files given as arguments; exits 1 if there are any.
set -euo pipefail

jq -r '
  .runs[] |
  ([.tool.driver, .tool.extensions[]?] | map(.rules // []) | add | map({(.id): .}) | add // {}) as $rules |
  .results[]? | $rules[.ruleId] as $rule |
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
