#!/bin/bash
STATE=".claude/session-state.md"
BRANCH=$(git branch --show-current 2>/dev/null || echo "unknown")
COMMIT=$(git log --oneline -1 2>/dev/null || echo "none")
MODIFIED=$(git diff --name-only HEAD 2>/dev/null | head -10 | tr '\n' ', ')
printf "# Session State — %s\n- Branch: %s\n- Commit: %s\n- Modified: %s\nRe-read CLAUDE.md on resume.\n" \
  "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "$BRANCH" "$COMMIT" "$MODIFIED" > "$STATE"
echo "State saved." >&2
