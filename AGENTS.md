# Agent instructions

- Work directly on `main`. Keep commits signed and history linear; no branches or PRs.
- Ship small usable changes promptly. Do not follow TDD or add tests by default.
- Run only local checks relevant to the change; CI only builds and releases every commit.
- Android device checks use real ADB device `atlas.mesh:5555`. Never use emulators.
- Preserve native editing, composition, Undo/Redo, and existing user data.
- Keep documentation and tooling minimal.
