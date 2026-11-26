#!/bin/bash
# Install git hooks for szork development

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_DIR="$(git rev-parse --git-path hooks)"

echo "Installing git hooks..."

for hook in pre-commit commit-msg; do
    cp "$SCRIPT_DIR/$hook" "$HOOKS_DIR/$hook"
    chmod +x "$HOOKS_DIR/$hook"
done

echo "Git hooks installed successfully!"
echo "pre-commit checks formatting and compilation; commit-msg requires a Signed-off-by line (git commit -s)."
