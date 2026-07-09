#!/bin/bash
INPUT=$(cat)
NOTE=$(echo "$INPUT" | jq -r '.notification // empty')
[ -z "$NOTE" ] && exit 0
echo "$NOTE" | grep -qiE '(failed|error|blocked|complete|PR created|quality gate)' || exit 0
EMOJI="🤖"
echo "$NOTE" | grep -qiE '(failed|error|blocked)' && EMOJI="🚨"
echo "$NOTE" | grep -qiE '(complete|passed|PR created)' && EMOJI="✅"
[ -z "${SLACK_WEBHOOK_URL:-}" ] && exit 0
curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"text\": \"${EMOJI} Claude Pipeline [$(basename $(pwd))]: ${NOTE}\"}" \
  "$SLACK_WEBHOOK_URL" > /dev/null 2>&1
