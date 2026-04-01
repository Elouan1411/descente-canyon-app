# Agent Notes

## Parallel Tooling

- Do not run multiple `bash` commands through `multi_tool_use.parallel` in this repository.
- In this environment, parallel `bash` calls can hang or get aborted, especially with Gradle commands.
- Run shell commands sequentially instead, even when they look independent.
- `multi_tool_use.parallel` is safe to prefer for read-only search tools like `glob`, `grep`, and `read`.

## Gradle Validation

- Prefer one Gradle command at a time.
- Run unit tests first, then Android test compilation as a separate command if needed.
- Avoid starting two Gradle tasks in parallel from separate tool calls.
