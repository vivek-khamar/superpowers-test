#!/bin/bash
INPUT=$(cat)
CMD=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
block() { echo "{\"decision\": \"block\", \"reason\": \"$1\"}"; exit 0; }
echo "$CMD" | grep -qE '(rm -rf /|rm -rf \.|DROP TABLE|DROP DATABASE)' && block "Blocked: destructive command."
echo "$CMD" | grep -qE 'git push.*(--force|-f).*(main|master|develop)' && block "Blocked: force push to protected branch."
echo "$CMD" | grep -qE '(\.env|application-prod\.properties|\.pem|\.key)' && block "Blocked: sensitive file access."
echo "$CMD" | grep -qE 'mvn.*(deploy|release:prepare|release:perform)' && block "Blocked: use CI/CD for deploy."
echo "$CMD" | grep -qE '(kubectl (apply|delete)|terraform (apply|destroy))' && block "Blocked: infra changes need human approval."
echo '{"decision": "allow"}'
