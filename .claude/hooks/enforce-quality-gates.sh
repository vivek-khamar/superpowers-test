#!/bin/bash
FAILURES=""
PASSED=true
run_gate() {
  local NAME=$1; shift
  echo "[Gate] $NAME..." >&2
  OUT=$("$@" 2>&1) || { PASSED=false; FAILURES="${FAILURES}\n\n❌ ${NAME}:\n${OUT}"; }
  [ "$PASSED" = true ] && echo "[Gate] ✅ $NAME" >&2
}

# ── Skip entirely if we're not on the active ticket's own branch yet ──────────
# The Stop hook can fire on intermediate turn pauses (e.g. while an async
# exploration subagent is still running), long before any worktree/branch for
# this ticket exists. At that point the main checkout may still be sitting on
# a stale, unrelated branch from prior work — gating against that branch's
# already-existing, unrelated violations is meaningless and can never pass.
EXPECTED_BRANCH=$(grep -m1 '^\- \*\*Branch\*\*:' .claude/sprint-context.md 2>/dev/null | sed -E 's/.*Branch\*\*:[[:space:]]*//')
CURRENT_BRANCH=$(git branch --show-current 2>/dev/null)
if [ -n "$EXPECTED_BRANCH" ] && [ "$CURRENT_BRANCH" != "$EXPECTED_BRANCH" ]; then
  echo "[Gate] Not yet on ticket branch ($EXPECTED_BRANCH) — currently on '$CURRENT_BRANCH'. Skipping." >&2
  echo '{"decision": "allow"}'; exit 0
fi

# ── Find a base ref to diff against, so style/static-analysis gates only ──────
# cover files this ticket actually touched, not pre-existing repo-wide debt.
BASE_REF=""
for CANDIDATE in origin/develop origin/main origin/master develop main master; do
  if git rev-parse --verify -q "$CANDIDATE" >/dev/null 2>&1; then
    MERGE_BASE=$(git merge-base HEAD "$CANDIDATE" 2>/dev/null) && [ -n "$MERGE_BASE" ] && { BASE_REF="$MERGE_BASE"; break; }
  fi
done

CHANGED_JAVA_FILES=""
if [ -n "$BASE_REF" ]; then
  CHANGED_JAVA_FILES=$(git diff --name-only --diff-filter=ACMR "$BASE_REF" -- '*.java')
fi

run_gate "Unit Tests"        ./mvnw test -q
run_gate "Integration Tests" ./mvnw verify -q -DskipUnitTests

if [ -n "$CHANGED_JAVA_FILES" ]; then
  CHECKSTYLE_INCLUDES=$(echo "$CHANGED_JAVA_FILES" | sed -E 's#^src/(main|test)/java/##' | paste -sd, -)
  SPOTBUGS_CLASSES=$(echo "$CHANGED_JAVA_FILES" | sed -E 's#^src/(main|test)/java/##; s#\.java$##; s#/#.#g' | paste -sd, -)
  run_gate "Checkstyle (changed files)" ./mvnw checkstyle:check -q "-Dcheckstyle.includes=$CHECKSTYLE_INCLUDES"
  run_gate "SpotBugs (changed files)"   ./mvnw spotbugs:check -q "-Dspotbugs.onlyAnalyze=$SPOTBUGS_CLASSES"
else
  echo "[Gate] No changed .java files vs base branch — skipping Checkstyle/SpotBugs" >&2
fi

if [ "$PASSED" = true ]; then
  echo "[Gate] ✅ ALL PASSED" >&2
  echo '{"decision": "allow"}'; exit 0
fi
MSG="Fix ALL gate failures:\n${FAILURES}\nDo NOT skip failing tests."
ESCAPED=$(echo -e "$MSG" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
echo "{\"decision\": \"block\", \"reason\": $ESCAPED}"
