# Commander Pi

A Commander-only adaptation of [Neo Forge](https://github.com/AdrianLopez98/NeoForge), using the [Forge](https://github.com/Card-Forge/forge) rules engine. The intended device is a Raspberry Pi 5 with a custom controller. Current development and verification are on Windows; Linux ARM64/Pi validation is still pending.

## Current baseline

The main menu contains Commander, Card Encyclopedia, and Customize. Non-Commander mode controllers and screens have been removed. Shared rules, card scripts, deck tools, settings, replays, and match controls remain. The application no longer depends on Forge's Adventure/mobile engine. ChatGPT opponent integration is not implemented.

The baseline compiled successfully with JDK 17 and Maven 3.9. A separate integration check completed a four-player Commander game on turn 39 and checked 40 starting life, commander registration, preconstructed decks, encyclopedia loading, and deck storage. GUI checks covered the menu, encyclopedia, customization, and game table. This is not a claim that the upstream Maven test suite passed. The engine logged recoverable AI fallback warnings; ongoing real-play testing is needed. No measured performance comparison or Pi test has been completed.

## Build

Use JDK 17 and Maven 3.9. Clone Forge at the revision in `upstream.json` into a separate directory, copy this repository's `forge-gui-neo` directory into it, and add `<module>forge-gui-neo</module>` to the root POM's `<modules>` section. From the Forge root run:

```sh
mvn -B package -Dmaven.test.skip=true -Dcheckstyle.skip=true -pl forge-gui-neo -am
```

The `-am` option is required for the reactor's parent/dependency resolution. The module's `target` directory contains the application JAR and runtime dependencies. Package with `python scripts/package_windows.py --forge <forge-directory> --output <new-output-directory>` on Windows, with `JAVA_HOME` pointing to JDK 17. This packaging script preserves the resource selection used in the verified local build; its portable path handling was added for this repository.

The integration runner is `forge-gui-neo/verification/CommanderSmoke.java`. Compile it against `app/*` in the packaged application and run `CommanderSmoke` with the launch arguments and a disposable `neo.dataDir`. This launches JavaFX and requires a desktop session. Inspect the source before running it.

## Source and licensing

`upstream.json` pins both original source revisions and downloaded archive checksums. The Forge engine is obtained separately at that pinned revision; it is not duplicated in this repository. `docs/source-changes.json` records the initial pruning. This repository starts from a source snapshot, not the full upstream Git history.

Original notices and GPLv3 license are preserved in `LICENSE` and `AVISOS.txt`. Existing third-party resource notices remain with the source. This modified project is not an official Neo Forge or Wizards of the Coast release.

`upstream-reference/` preserves the original build documentation and Linux/macOS packaging files for reference. They describe the full upstream application and have not been adapted or validated for this fork. Their workflows are deliberately outside `.github/workflows` so they do not publish incorrect builds automatically.

## Packaging boundary

Exclude Adventure, Conquest, cube, Draft, puzzles, Sealed, and tutorial resources. Keep `res/quest/commanderprecons`, and only `Commander.dat` under `res/deckgendecks`. Keep every card and token script plus shared art, fonts, audio, AI and metadata. Shared format metadata in the rules engine remains necessary even though those formats are not exposed as playable modes.

Build outputs, personal game profiles, caches, downloaded tools and credentials are not included.
