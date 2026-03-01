#!/usr/bin/env bash
# Install claude-android commands and skills into ~/.claude/
# Run after cloning or after `git pull` to pick up changes.
set -e

REPO="$(cd "$(dirname "$0")" && pwd)"
CLAUDE_DIR="$HOME/.claude"

echo "Installing claude-android toolkit..."

# Commands — copy each .md file to ~/.claude/commands/
mkdir -p "$CLAUDE_DIR/commands"
for f in "$REPO/commands/"*.md; do
  cp "$f" "$CLAUDE_DIR/commands/$(basename "$f")"
  echo "  command: $(basename "$f")"
done

# Skills — copy each skill directory to ~/.claude/skills/
mkdir -p "$CLAUDE_DIR/skills"
for d in "$REPO/skills/"/*/; do
  skill="$(basename "$d")"
  rm -rf "$CLAUDE_DIR/skills/$skill"
  cp -r "$d" "$CLAUDE_DIR/skills/$skill"
  echo "  skill:   $skill"
done

echo ""
echo "Done. Restart Claude Code for changes to take effect."
