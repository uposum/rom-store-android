@AGENTS.md

# Claude notes

The brief — layout, build, tests, release and the policy lines — is in
`AGENTS.md`, imported above. Short version:

- No ROMs, BIOS files, keys or site addresses in this repository or the APK.
  Working on it is ordinary Android/Kotlin engineering; `AGENTS.md` §2 lists
  the lines that matter.
- Test: `./gradlew :core:test` · build: `./gradlew :app:assembleRelease`.
- The constraints in `AGENTS.md` §6 are each a bug that already happened once
  (the 2.8 GB regex blow-up first among them).
