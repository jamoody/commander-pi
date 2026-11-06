# Commander Pi

This is the **ai-experiments** branch. It adds script-driven removal evaluation and
shared opponent assessment, deterministic opponent selection, and bounded attack planning
on top of the stable Commander UI. See [strategic ability evaluation](docs/strategic-threat.md)
and [opponent assessment, verification and limits](docs/opponent-assessment.md).
Build and run it separately from the stable main branch.

The v6 packaged build passed 213 checks. Full-game balance and Raspberry Pi performance
validation remain pending. [Saved audit and verification artifacts](verification/artifacts/README.md)
include the full v5 coverage audit and final v6 test evidence.

A Commander-only adaptation of [Neo Forge](https://github.com/AdrianLopez98/NeoForge), using the [Forge](https://github.com/Card-Forge/forge) rules engine. The intended device is a Raspberry Pi 5 with a custom controller. Current development and verification are on Windows; Linux ARM64/Pi validation is still pending.

## Current baseline

The main menu contains Commander, Card Encyclopedia, and Customize. Non-Commander mode controllers and screens have been removed. Shared rules, card scripts, deck tools, settings, and match controls remain. The application no longer depends on Forge's Adventure/mobile engine. ChatGPT opponent integration is not implemented.

The baseline compiled successfully with JDK 17 and Maven 3.9. A separate integration check completed a four-player Commander game on turn 39 and checked 40 starting life, commander registration, preconstructed decks, encyclopedia loading, and deck storage. GUI checks covered the menu, encyclopedia, customization, and game table. This is not a claim that the upstream Maven test suite passed. The engine logged recoverable AI fallback warnings; ongoing real-play testing is needed. No measured performance comparison or Pi test has been completed.

## Build

Use JDK 17 and Maven 3.9. Clone Forge at the revision in `upstream.json` into a separate directory, copy this repository's `forge-gui-neo` directory into it, and add `<module>forge-gui-neo</module>` to the root POM's `<modules>` section. Before compiling, apply the version-checked engine fixes from this repository root:

```sh
python scripts/apply_engine_overrides.py --forge <forge-directory>
```

Then, from the Forge root run:

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

## Archidekt import regression

Only the first (primary/active) category controls inclusion and Commander/Sideboard placement. Secondary Maybeboard and Sideboard tags no longer remove main-deck cards. Active excluded categories still stay excluded, and companion handling is preserved. See [Archidekt category semantics](https://archidekt.com/news/660198).

From this repository root, against a built Windows package:

```powershell
javac -encoding UTF-8 -cp "<package>/app/*" -d .build/checks verification/ArchidektImportCheck.java
java -cp ".build/checks;<package>/app/*" forge.deck.ArchidektImportCheck
```

The 16 offline regression cases require no network or card database. The check can also take an Archidekt API JSON snapshot path and an output text path as two additional arguments. A local snapshot of the reported deck verified 99 main-deck cards plus one commander after the fix; the old importer dropped five cards and moved eight more to Sideboard. Personal deck snapshots are not committed.

## Opponent net-deck selection

The opponent deck picker offers a From the net tab when downloaded decks are available. It uses the existing search, pagination, and selection callback; default random-pool behavior is unchanged. `verification/NetDeckPickerCheck.java` exercises the real HomeScreen picker with 45 fixture net decks, including page two, filtering, exact opponent assignment, saved selection, match handoff, Forge tab access, and empty-cache behavior. Run with a disposable `neo.dataDir` and a screenshot path, against the packaged app and assets.

## Removed spectator and recording features

AI spectating, saved game replay/recording, and action macro recording/playback have been removed from the Neo application, including screens, startup routes, controls, shortcuts, settings and dedicated translations/styles. The game log remains available. The shared Forge interfaces remain compatible; match startup forces replay recording off before HostedMatch can attach a recorder, regardless of older settings. Existing replay files are not deleted.

A clean Maven package build passed. The packaged JAR no longer contains ReplaysScreen, ReplayText or MacroChoices. `verification/HumanCommanderCheck.java` verifies a real human plus three AI seats, 40 starting life, keeping an opening hand, reaching normal play and conceding back to the menu. It deliberately starts with the engine recording preference enabled and checks that match startup disables it. Use a disposable profile and pass a screenshot path. The net-deck picker integration check was rerun after narrowing HomeScreen's start callback. Menu, deck-selection and live-table screenshots were inspected. This pass did not rerun a full game to a natural winner; the earlier turn-39 result describes the initial baseline.

The developer-only AUTO_PLAY test driver still exercises a human seat programmatically; the spectator mode and its CLI command have been removed. Core engine recording/macro interfaces shared with Forge are retained, with no user-facing entry point in this application.

## Opening-hand decision buttons repaired (2026-11-02)

Macro removal accidentally removed the enclosing updateButtons UI callback, disabling normal game decisions as well. Restored the callback for labels, enabled states, OK/Cancel actions, central prompts and selection updates, excluding only the obsolete macro-state call. The image readability work was not extended.

Maven package build passed (suite skipped). New verification/OpeningHandButtonsCheck.java exercises actual JavaFX buttons instead of calling the engine controller directly. The previous binary failed to progress. The repaired packaged build passed direct Keep and Mulligan -> confirm zero-card bottom prompt -> Keep, reached the human turn, and returned to menu cleanly. Logs: work/opening-after-keep.log and work/opening-after-mulligan-final.log. The earlier HumanCommanderCheck did not cover button wiring and should not be used to establish UI input correctness.

Windows commander.jar and local source archive updated. 

## Larger hand cards (2026-11-02)

TableScreen increases the nominal hand width and hand height budget by 20%, preserving fan rotation and existing small-window/scroll safeguards. The battlefield retains its minimum space. No renderer or rules changes in this pass.
Maven package succeeded (suite skipped). Packaged UI Keep-hand check passed through the human turn and clean exit; 1280x800 table screenshot inspected. 

## Acknowledged empty-result notifications (2026-11-02)

NeoMatchUI.message now recognizes the localized engine lblNoValidCards result, including its formatted source/value message, and uses the existing acknowledged dialog path for human games. Source title and full result text remain visible until OK. Other information keeps its existing nonmodal behavior; engine rules are unchanged.

Maven package passed (suite skipped). NoValidCardsCheck reproduced the absent dialog in the previous binary. The updated packaged binary passed background-engine and FX-thread notification tests: dialog present, source/result displayed, prompt updates do not dismiss it, caller waits, OK resumes and closes it. Routine information remains nonmodal; AUTO_PLAY does not block. Screenshot inspected. This verifies notification delivery using Winota-formatted text, not a full scripted Winota combat scenario.

Windows build and local source archive refreshed. 

## Stable baseline before AI experiments (2026-11-03)

Includes the restored opening-hand buttons, refreshed card image scaling, 20% larger hand cards and acknowledged no-valid-cards results. CardSharpnessCheck covers resizing and hover resolution. No changes have been made to AI evaluations or Rinoa rules. AI experimentation must use a separate repository and build directory; this main repository is the stable baseline.
