# Commander Pi

## Start here: project handoff (updated 2026-11-06)

This README is the living handoff for a new developer or Codex session. Read this section,
the build instructions, and the linked AI design notes before changing code. Update this
handoff after substantial work and before switching computers: record what changed,
what was actually verified, known limitations, and the next step. Historical notes below
and saved audit reports describe their own snapshots; this section describes current status.

### Goal and working preferences

- Build a dedicated four-player Commander simulator for a Raspberry Pi 5 and, later,
  a custom controller with Commander-focused buttons. Get the software right first.
- Develop and test on Windows for convenience; periodically validate on the physical Pi.
  There is no validated Pi build or Pi emulation environment yet.
- Keep Commander, Card Encyclopedia, and Customize. Remove unnecessary underlying
  features where safe, while preserving shared code and assets Commander requires.
- Improve usability and readability without speculative performance cuts. Measure on
  the Pi before making further performance tradeoffs; prefer existing settings when useful.
- Keep Forge responsible for rules and legality. Avoid unnecessary deep rules changes
  or changing correct rules behavior merely because its presentation is awkward.
- ChatGPT/API-driven opponents are not part of the current plan. Improve Forge's local AI;
  the user does not want per-game API costs or dependence on Codex usage for gameplay.
- Prefer general analysis of Forge's scripted abilities and reusable capability categories,
  not a growing list of named-card exceptions. New sets are represented by human-authored
  Forge card scripts; the evaluator does not interpret English card text with an LLM.
- For opponent choice, distinguish strategic threat from combat power and the value of
  a specific action. Do not use hand size as a generic threat bonus or random opponent
  selection. Assess whether an attack is useful, survivable, or actually lethal.
- Batch Git commits/uploads at milestones or when requested rather than after every small
  change. Keep this README current locally in between; push before a computer handoff.

### Branches and isolation

Repository: https://github.com/jamoody/commander-pi

- `main`: stable Commander UI baseline; no experimental AI evaluator.
- `ai-experiments`: this branch, containing that UI baseline plus experimental AI changes.
  "AI fork" in prior discussions means this branch and its separate local checkout/build.
- Keep AI changes off `main`. Put shared UI fixes on `main` and bring those commits into
  `ai-experiments` by merge or selective cherry-pick as appropriate. Inspect existing changes
  first; do not replace the AI branch with the stable files or merge experiments back casually.
- Use separate source/build directories, packaged applications and test profiles for stable
  and experimental builds. Do not overwrite the user's running stable installation.

The AI implementation, tests, documentation and audit archives were uploaded in commit
`712ae77238919f9cafb98bebb6592cbaeab954e0`. This handoff update follows it. That upload left
`main` unchanged at `0fbd146bffe110f72d3a6ba14e64f9a4a6e7fdd4`.

### Current state and next work

The Commander UI changes listed below are implemented. The AI branch now adds a general
strategic-ability evaluator and v6 shared opponent assessment, deterministic opponent
selection, bounded attack planning, and integrated removal/burn/discard targeting.
These additions supplement Forge's existing evaluation of ordinary creatures; a zero
strategic bonus does not by itself mean a creature is unrecognized or incorrectly scored.
Coverage is not proof of good decisions. Do not pursue zero uncovered cards with increasingly
specific rules: the next major step is real-game testing of targeting and attack decisions.

The v6 packaged build passed **213 checks**. The AI reactor build passed with Checkstyle;
upstream tests were skipped. Full-match balance, improved win rate, and Raspberry Pi
performance remain **unverified**. The earlier completed four-player baseline game is not
evidence that the new AI has completed full-match validation.

Next steps:

1. On a new machine, reconstruct a build from the pinned source using the instructions below.
   Read the verification sources for their required launch arguments, resources and profiles.
2. Play/test the AI build and compare its choices with the baseline comparison launcher.
   Capture concrete board states and decisions before tuning scores or expanding scope.
3. Continue requested UI fixes while preserving the stable/experimental branch boundary.
4. Validate Linux ARM64 packaging, JavaFX/native dependencies, usability and speed on a real
   Pi 5 before claiming device support or choosing further optimizations.

Read [strategic-threat.md](docs/strategic-threat.md) for the general evaluator and
[opponent-assessment.md](docs/opponent-assessment.md) for v6 integration and limitations.
[meaningful-coverage-audit.md](docs/meaningful-coverage-audit.md) and the numbered audit notes
explain coverage methodology. [Saved verification evidence](verification/artifacts/README.md)
links the committed v5 and v6 ZIP snapshots. Older statements in reports about work being
local or unpushed describe report-generation time, not the current GitHub state.

### Continuing on another computer

Clone the repository with `git clone --branch ai-experiments https://github.com/jamoody/commander-pi.git`
and open that folder in Codex. Ask the new session to read this README and the linked design
notes. GitHub carries the source and evidence, not the full conversation, installed build
tools, downloaded Forge source, generated executables, card-image cache or personal decks
and profiles. Rebuild or copy needed local assets separately; never commit credentials or
personal profiles. Commit/push before leaving one computer and fetch/pull before editing
on another. Check for local changes before synchronizing; do not discard them.

On the original Windows machine, the experimental checkout is
`C:/Users/moody/Documents/Codex/2026-10-29/do/work/commander-pi-ai` and generated packages are
under `C:/Users/moody/Documents/Codex/2026-10-29/do/outputs/Commander-AI-Windows` and
`Commander-Windows`. These are local reference paths, not prerequisites on a new machine.
The repository is an overlay: obtain the pinned Forge engine separately, then apply the
Neo UI sources and version-checked engine overrides. Do not substitute latest upstream.

The Windows packager generates a baseline comparison launcher with both
`-Dforge.ai.disableStrategicThreat=true` and `-Dforge.ai.disableOpponentAssessment=true`.
This disables both experimental evaluators for comparison; it is not a byte-identical
historical build. Specialist legacy AI paths still exist, and randomness has not been
removed globally from Forge or from Magic's rules.

## AI branch overview

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

This historical stable snapshot includes the restored opening-hand buttons, refreshed card image scaling, 20% larger hand cards and acknowledged no-valid-cards results. CardSharpnessCheck covers resizing and hover resolution. At that point no AI evaluations had changed. Rinoa's token-death behavior was investigated and left unchanged because it followed the rules. Subsequent AI work is now on `ai-experiments`, as described in the current handoff above; `main` remains the stable baseline.
