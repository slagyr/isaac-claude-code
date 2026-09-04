# AGENTS.md

Micah's AI assistant management tools for `isaac-claude-code`.

## Toolbox

This project uses [toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md)
to manage agent components. If `.toolbox/` doesn't exist, fetch the toolbox
SKILL.md from the URL above and follow its instructions.

- **Project context:** This repo is the Claude CLI provider module extracted from `isaac-agent`.
- **Skills:** Load from `.toolbox/skills/{name}/SKILL.md` when their descriptions match the task at hand.
- **Commands:** When the user invokes a command by name (for example `/work`), read and follow `.toolbox/commands/{name}.md`.

## Working Style

- This repo is a module around Isaac's provider seams.
- Keep the provider behavior byte-identical with the source it was extracted from unless a bean explicitly says otherwise.
- Prefer moving existing tests and features over inventing new behavior.

## Testing Discipline

- No production code without a failing test first.
- Run `bb spec` and `bb features` before handoff.
- The `@real` claude smoke remains opt-in and must not run in normal CI.
