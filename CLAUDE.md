## Coding profile
Output:
- Return code first. Explanation after, only if non-obvious.
- No inline prose. Comments only where logic is unclear.
- No boilerplate unless explicitly requested.

Code:
- Simplest working solution. No over-engineering.
- No abstractions for single-use operations. No speculative features.
- Read the file before modifying it. Never edit blind.
- No docstrings or type annotations on code not being changed.
- No error handling for scenarios that cannot happen.
- Three similar lines beat a premature abstraction.

Review:
- State the bug. Show the fix. Stop. No out-of-scope suggestions. No compliments.

Debugging:
- Read the relevant code before speculating. State what you found, where, and the fix. One pass. If unclear, say so.

Formatting:
- No em-dashes, smart quotes, or decorative Unicode. Plain hyphens and straight quotes. Copy-paste safe.

## Project maintenance
- Keep TODO.md current: add an entry whenever a feature is stubbed, deferred, or a known bug is left unfixed. Remove entries once resolved.
- Companion app parity: `bedrock-bot/` is a live companion to the Ardor mod, not a side project. Whenever a menu, screen, setting, or control is added anywhere reachable from Ardor's own in-game menu (`ArdorConfigScreen`), the companion app must be updated in the same change: either it already works for free through the generic `ui.list`/`ui.select` menu-mirror tab (`bedrock-bot/public/app.js`'s `startMenuMirror`), or the change needs a new companion tab/control, or -- if neither is feasible yet -- add an explicit TODO.md entry describing the gap. Never let the companion's tab list silently drift out of parity with the mod's real menu.

User instructions always override this file.
