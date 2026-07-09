#!/bin/bash
CONTEXT=".claude/sprint-context.md"
if [ -f "$CONTEXT" ]; then
  echo "========================================"
  echo "  SPRINT CONTEXT LOADED"
  echo "========================================"
  cat "$CONTEXT"
  echo "========================================"
else
  echo "WARNING: No sprint context at $CONTEXT"
  echo "Run: ../sp-pipeline/scripts/run.sh --fetch PROJ-42"
fi
