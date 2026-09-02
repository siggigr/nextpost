# Nextpost

A native Android app for creating and playing GPS-based scavenger hunts. A **game creator** places a sequence of real-world posts on a map and writes a graded set of clues leading from each post to the next. **Players** join with a short code, walk the route, and reveal clues one at a time — the fewer they need, the higher their score.

Built solo, from spec to shipped app, as a portfolio project. The full design document — including the reasoning behind every non-obvious decision — is in [`Nextpost-project-plan.md`](./Nextpost-project-plan.md).

## What it does

- **Create a game**: place posts on a live map, write 3–10 progressively less vague clues for each, publish to get a shareable 6-character code.
- **Join and play**: enter a code, walk to the marked start, and each arrival reveals the next set of clues. Score decreases the more clues you open, on a curve tuned to reward confident navigation without punishing a struggling player down to zero.
- **My games**: manage drafts and published games, review a route on the map, delete a game (and everything under it) cleanly.
- Runs fully in Icelandic or English, following the device's system language.

## Why it's a decent thing to look at

This wasn't a weekend toy — it went through nine milestones with real device testing, a written spec with acceptance criteria, and several genuinely interesting engineering problems along the way:

- **A server-enforced progressive-disclosure security model.** A player can only ever read the post they're currently hunting for and the clues they've paid points to open — enforced by Firestore security rules, not by the UI politely hiding things. Proven three separate ways: the Firebase Rules Playground, a local emulator test suite exercising the real deployed rules, and a live pass with two independent accounts.
- **A pure, fully unit-tested scoring engine.** The point curve (`ScoreCalculator`) lives with zero Android or Firebase imports, tested with a property test asserting the score strictly decreases across every valid clue count until a floor is reached exactly once — the kind of test that catches an edge case (a "free" stretch of clues on longer posts) that nine hand-picked examples never would.
- **A five-round cross-device debugging investigation.** A character-loss bug in the clue editor survived four plausible Compose-state fixes before a two-minute comparison against a different keyboard (Samsung vs. Gboard) revealed it was never a state bug at all. Documented in the plan as a standing rule: test on real hardware, not just the emulator.
- **A properly reviewed localisation**, not a machine-translated afterthought — every Icelandic string was hand-checked for grammar, gender agreement, and consistent in-game vocabulary.

## Architecture

```
domain/       Pure Kotlin — no Android or Firebase imports. Scoring, clue
              validation, proximity/arrival math. Runs on the plain JVM
              test source set.
data/         Firestore access, repositories, model classes.
ui/           Jetpack Compose screens, one package per flow
              (home, create, join, play, mygames).
```

- **UI**: Jetpack Compose, Material 3
- **Maps**: Google Maps SDK via Maps Compose
- **Backend**: Firebase — Firestore, Anonymous Auth
- **Architecture**: MVVM — Compose → ViewModel (`StateFlow`) → Repository → Firestore
- **Security**: Firestore security rules enforcing session-scoped, progressive access — not client-side gating

## Notable engineering decisions

- Business logic (scoring, clue validation, arrival detection) is kept strictly out of the ViewModel layer, in a `domain/` package with no platform dependencies — testable on a laptop with no emulator running.
- Clues are stored as individual Firestore documents rather than an array field, specifically so security rules can gate access to them one at a time.
- Firestore's default offline persistence is relied on deliberately rather than fought — most transient network drops resolve themselves with no error state needed, which changed what "error handling" actually meant for this app.
- The arrival radius and its GPS-accuracy rejection threshold are derived from a single constant, after a field-testing pass in real weather conditions revealed the initial value was too generous.

## Status

All nine planned milestones (M0–M8) complete: authentication and map rendering, location permissions, the full create/publish/join/play loop, scoring, and hardening informed by real-device and two-account testing.

Deferred, on purpose, and written up with reasoning in the project plan: a post-game leaderboard, a first-launch help screen, AI-assisted clue suggestions, and an in-app language picker — none needed to ship a solid v1, each parked rather than built speculatively.

## Running it

Requires a Firebase project (Firestore + Anonymous Auth) and a Google Maps API key. See the project plan's setup walkthrough for the full sequence — Firebase project creation, Maps key restriction, and the Secrets Gradle Plugin wiring that keeps the key out of version control.

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

---

Built with Kotlin, Jetpack Compose, and a lot of walking around Kópavogur testing GPS accuracy in the rain.
