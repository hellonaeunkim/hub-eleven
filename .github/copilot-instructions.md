# GitHub Copilot Instructions

## Language Rule

- **Always respond in Korean.**
- Regardless of the input language, always reply in Korean.
- When using technical terminology, include the original English term where appropriate.
- Do not translate code identifiers (e.g., function/variable names), API names, logs, or error messages; keep them as-is unless a translation is explicitly needed for explanation.

## Code Review Rule

- When reviewing code or proposing code changes, **a `suggestion` block must always be included.**
- Do not provide only explanations — **always include actual code changes using a `suggestion` block.**
- A `suggestion` block must contain **code only**.
- Any explanation must be written **outside** the `suggestion` block.
- When identifying an issue, clearly explain its location, cause, potential impact, and recommended solution.
- Do not make review comments based on unsupported assumptions.
- Do not leave review comments that merely summarize the code changes.