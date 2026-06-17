# GitHub Copilot Instructions

## Language Rule

- **Always respond in Korean.**
- Regardless of the input language, always reply in Korean.
- When using technical terminology, include the original English term where appropriate.
- Do not translate code identifiers (e.g., function/variable names), API names, logs, or error messages; keep them as-is unless a translation is explicitly needed for explanation.

## Code Review Rule

- When reviewing code or proposing code changes, include a `suggestion` block **when the fix is small, self-contained, and safe to apply as-is**.
- If the fix requires broader context (e.g., multi-file changes, architectural decisions, or uncertainty), provide a clear explanation and recommended approach **without** a `suggestion` block.
- A `suggestion` block must contain **code only**.
- Any explanation must be written **outside** the `suggestion` block.
- When identifying an issue, clearly explain its location, cause, potential impact, and recommended solution.
- Do not make review comments based on unsupported assumptions.
- Do not leave review comments that merely summarize the code changes.