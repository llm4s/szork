#!/usr/bin/env python3
"""
Script to migrate all Scala files to use the new error handling system.
"""

import os
import re
import sys

def add_imports(content):
    """Add necessary imports if not already present"""
    if "import org.llm4s.szork.error._" not in content:
        # Find the last import line
        import_lines = []
        for i, line in enumerate(content.split('\n')):
            if line.startswith('import '):
                import_lines.append(i)

        if import_lines:
            last_import_idx = import_lines[-1]
            lines = content.split('\n')
            # Add imports after last import
            lines.insert(last_import_idx + 1, "import org.llm4s.szork.error._")
            lines.insert(last_import_idx + 2, "import org.llm4s.szork.error.ErrorHandling._")
            content = '\n'.join(lines)
        else:
            # Add after package declaration
            lines = content.split('\n')
            for i, line in enumerate(lines):
                if line.startswith('package '):
                    lines.insert(i + 1, "")
                    lines.insert(i + 2, "import org.llm4s.szork.error._")
                    lines.insert(i + 3, "import org.llm4s.szork.error.ErrorHandling._")
                    break
            content = '\n'.join(lines)

    return content

def migrate_file(filepath):
    """Migrate a single Scala file"""

    # Skip already migrated files and error handling files themselves
    if 'error/SzorkError.scala' in filepath or 'error/ErrorHandling.scala' in filepath:
        return False

    with open(filepath, 'r') as f:
        content = f.read()

    original_content = content

    # Check if file needs migration
    needs_migration = False
    if 'Either[String,' in content or 'Either[List[String],' in content:
        needs_migration = True

    if not needs_migration:
        return False

    # Add imports
    content = add_imports(content)

    # Replace type signatures
    content = re.sub(r':\s*Either\[String,\s*([^]]+)\]', r': SzorkResult[\1]', content)
    content = re.sub(r':\s*Either\[List\[String\],\s*([^]]+)\]', r': SzorkResult[\1]', content)

    # Replace Left(string) with appropriate error types based on context
    # This is a simplified heuristic - may need manual refinement

    # For persistence operations
    if 'save' in filepath.lower() or 'persistence' in filepath.lower():
        content = re.sub(r'Left\(([^)]+)\)', r'Left(PersistenceError(\1, "operation"))', content)

    # For parsing operations
    elif 'parse' in filepath.lower() or 'parser' in filepath.lower():
        content = re.sub(r'Left\(([^)]+)\)', r'Left(ParseError(\1))', content)

    # For validation
    elif 'valid' in filepath.lower():
        content = re.sub(r'Left\(([^)]+)\)', r'Left(ValidationError(List(\1)))', content)

    # For media generation
    elif 'music' in filepath.lower():
        content = re.sub(r'Left\(([^)]+)\)', r'Left(MusicGenerationError(\1))', content)
    elif 'tts' in filepath.lower() or 'speech' in filepath.lower():
        content = re.sub(r'Left\(([^)]+)\)', r'Left(AudioGenerationError(\1))', content)
    elif 'image' in filepath.lower():
        content = re.sub(r'Left\(([^)]+)\)', r'Left(ImageGenerationError(\1))', content)

    # For network operations
    elif 'network' in filepath.lower() or 'api' in filepath.lower():
        content = re.sub(r'Left\(([^)]+)\)', r'Left(NetworkError(\1, "service"))', content)

    # Default to GameStateError
    else:
        content = re.sub(r'Left\(([^)]+)\)', r'Left(GameStateError(\1))', content)

    # Make loggers implicit if they're not already
    content = re.sub(r'private val logger = LoggerFactory', r'private implicit val logger = LoggerFactory', content)

    # Write back only if changed
    if content != original_content:
        with open(filepath, 'w') as f:
            f.write(content)
        print(f"Migrated: {filepath}")
        return True

    return False

def main():
    """Migrate all Scala files in the project"""
    src_dir = 'src/main/scala'

    migrated_count = 0
    for root, dirs, files in os.walk(src_dir):
        for file in files:
            if file.endswith('.scala'):
                filepath = os.path.join(root, file)
                if migrate_file(filepath):
                    migrated_count += 1

    print(f"\nMigration complete. {migrated_count} files migrated.")

    # List files that might need manual review
    print("\nFiles that may need manual review:")
    manual_review = [
        'TypedWebSocketServer.scala',
        'SzorkServer.scala',
        'StreamingAgent.scala',
        'GameTools.scala'
    ]
    for file in manual_review:
        print(f"  - {file}")

if __name__ == '__main__':
    main()