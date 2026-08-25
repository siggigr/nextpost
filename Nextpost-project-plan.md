# Nextpost — Project Plan and Build Spec

**Purpose of this document:** hand this to Claude Code (or any agent/IDE assistant) as the starting brief for the project. It defines scope, stack, data model, screens, rules, and a milestone order to build in.

**Two numbering systems, and they are unrelated.** *Sections* (1 to 15) are reference chapters describing **what** the app is; read them for detail on a topic. *Milestones* (M0 to M8, listed in section 10) describe **when** things get built; work through them in order. Section 4 is not milestone M4, and no section corresponds to a milestone. Section 5 in particular spans four milestones:

| Part of section 5 | Built in |
|---|---|
| 5.1 Home, 5.2 Create game flow | M2 |
| 5.2 Publish and code confirmation, 5.3 Join screen | M4 |
| 5.3 Play screen | M5 |
| 5.3 Game complete | M6 |

So section 5 stays incomplete until M6, by design.

**Status:** M0 to M3 complete and verified on device. M4 in progress. Section 12 lists decisions that still need confirming; everything else is a working default that can be changed.

---

## 1. Overview

Nextpost is an Android app for creating and playing GPS scavenger hunts. A *game creator* places a sequence of posts on a map and writes clues that lead from one post to the next. *Players* join with a code, see only the starting post, and work through the route clue by clue. Fewer clues opened means a higher score.

**Platform:** Android first (Kotlin + Jetpack Compose). iOS is a later consideration and is out of scope for v1, but the Firestore data model should stay platform-neutral so a second client can be added without a migration.

### Glossary

| Term | Meaning |
|---|---|
| Game creator | The user who builds a game and shares its code |
| Player | A user who joins and plays a game |
| Post | A geographic point in the route (`waypoint` in the original description) |
| Start post | Post index 0, the only post visible to a player at the beginning |
| Clue | A text hint that leads to a specific post. Clues belong to the post they lead *to* |
| Free clue | Clue index 0 of a post, revealed automatically with no score penalty |
| Extra clue | Any clue after the free one. Each costs points |
| Game code | Short unique code a player enters to join a game |
| Session | One player's playthrough of one game, holding progress and score |

---

## 2. Tech stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin | |
| UI | Jetpack Compose, Material 3 | Single-activity, Compose Navigation |
| Maps | `com.google.maps.android:maps-compose` + Play Services Maps | Compose wrapper over the Maps SDK |
| Location | `FusedLocationProviderClient` | Play Services Location |
| Backend | Firebase | Auth, Firestore, later Storage |
| Auth | Firebase Auth, anonymous sign-in on first launch | Anonymous account can be upgraded to email later without losing data |
| Firestore region | `europe-west2` | Same as DayPlan, keeps latency sane from Iceland |
| Architecture | MVVM: Compose screens → ViewModel (`StateFlow`) → Repository → Firebase | |
| DI | Manual constructor injection for v1 | Add Hilt only if wiring gets painful |
| Min SDK | 26 | |
| Target SDK | Current stable | |

### Package layout

```
is.siggi.nextpost
├── MainActivity.kt
├── navigation/            # NavHost, routes, arguments
├── ui/
│   ├── home/              # HomeScreen
│   ├── create/            # CreateGameScreen, PostEditorSheet, ClueEditorScreen, GameCodeScreen
│   ├── mygames/           # MyGamesScreen
│   ├── join/              # JoinGameScreen
│   ├── play/              # PlayScreen, ClueCard, GameCompleteScreen
│   ├── common/            # Reusable composables, permission handling
│   └── theme/
├── domain/
│   ├── ScoreCalculator.kt     # Pure, unit tested
│   ├── GameCodeGenerator.kt   # Pure, unit tested
│   └── ProximityChecker.kt    # Pure, unit tested
├── data/
│   ├── model/             # Game, Post, Clue, PlaySession
│   ├── repository/        # GameRepository, SessionRepository, LocationRepository
│   └── firebase/          # Firestore mappers, collection paths
└── util/
```

Everything in `domain/` must be free of Android and Firebase imports so it runs on the JVM test source set.

---

## 3. Data model (Firestore)

```
gameCodes/{CODE}
    gameId: String                  # lookup + uniqueness guard, CODE is the doc id

games/{gameId}
    code: String                    # "R7K2QM"
    title: String
    creatorUid: String
    status: String                  # "draft" | "published"
    postCount: Int                  # includes the start post
    scoredPostCount: Int            # postCount - 1
    defaultRadiusMeters: Int        # default 25
    createdAt: Timestamp
    publishedAt: Timestamp?

games/{gameId}/posts/{postId}
    index: Int                      # 0 = start post, then 1, 2, 3...
    title: String                   # optional label, e.g. "Bekkurinn við tjörnina"
    lat: Double
    lng: Double
    radiusMeters: Int
    clueCount: Int                  # 0 for the start post, >= 3 for all others

games/{gameId}/posts/{postId}/clues/{clueId}
    index: Int                      # 0 = free clue
    text: String

games/{gameId}/sessions/{uid}
    playerUid: String
    displayName: String
    status: String                  # "active" | "finished" | "abandoned"
    currentPostIndex: Int           # the post the player is currently hunting for
    cluesOpenedForCurrentPost: Int  # count of EXTRA clues opened, starts at 0
    postScores: Map<String, Double> # "1" -> 75.0, "2" -> 100.0
    totalScore: Double
    startedAt: Timestamp
    finishedAt: Timestamp?
```

### Model notes

- **Clues attach to the post they lead to.** *(Confirmed.)* The start post (index 0) has no clues, because nothing leads to it. Post 1 carries the clues that guide players from post 0 to post 1, and so on. A game with 4 posts therefore has 3 scored posts and 3 sets of clues.
- **The start post is a real arrival, not a formality.** A player joining a game travels to post 0 first. Arrival there is detected by the same radius check as every other post, awards no points, and triggers the reveal of the free clue for post 1. Until that arrival happens the player holds no clues at all. This means `currentPostIndex` starts at 0 and the target for post 0 is the only target ever drawn on the map.
- `cluesOpenedForCurrentPost` counts *extra* clues only. The free clue is not counted and costs nothing.
- Clues are separate documents, not an array field, so that security rules can gate them individually (see section 8).
- `postScores` is a map keyed by post index as a string, which avoids array-index writes and makes partial updates trivial.

---

## 4. Scoring

The formula below reproduces every example in the original description.

```kotlin
object ScoreCalculator {
    const val MAX_POINTS = 100.0
    const val MIN_POINTS = 10.0

    /**
     * @param totalClues   total clues attached to the post (>= 3 in a published game)
     * @param extraCluesOpened  clues opened beyond the free first clue
     */
    fun scoreForPost(totalClues: Int, extraCluesOpened: Int): Double {
        if (totalClues <= 1) return MAX_POINTS
        val penaltyPerClue = MAX_POINTS / (totalClues - 1)
        val raw = MAX_POINTS - extraCluesOpened * penaltyPerClue
        return maxOf(MIN_POINTS, raw)
    }
}
```

Display scores rounded to one decimal. Keep full precision in storage.

### Why clues per post are capped at 10

The 10-point floor is a `max()`, so a score can never rise by opening another clue. But it can go **flat**: once the floor is reached, further clues cost nothing and a player already at 10 may as well open everything. The flat zone is always the final 10% of clues, because the floor is hit when `extraCluesOpened >= 0.9 * (totalClues - 1)`.

At small counts this is harmless — with 3, 5 or 8 clues only the last one is affected. At 14 clues the last two are free; at 30, the last three.

**The fix is a cap of 10 clues per post, not a change to the formula.** Rescaling to remove the flat zone would break the worked examples above: the 5-clue case gives exactly 75 because the penalty is exactly 100/4, and those numbers come from the original description. With a maximum of 10 clues the penalty is 11.1 and the floor is reached only on the very last clue, so the flat zone disappears.

There is an independent reason for the cap. A post with 14 clues is a walkthrough, not a hint progression, and 5.2 requires the first clue to be the vaguest and the last a dead giveaway. Ten is already generous for that arc.

`MAX_CLUES_PER_POST = 10` belongs in `domain/` beside the minimum, enforced by the same validator, with **Add clue** disabled at the cap and a message explaining why.

### Required unit tests

| Total clues | Extra opened | Expected | Source |
|---|---|---|---|
| 3 | 0 | 100.0 | Explicit in description |
| 3 | 1 | 50.0 | Explicit in description |
| 3 | 2 | 10.0 | Explicit ("all three opened") |
| 5 | 1 | 75.0 | Explicit ("100 - 25 = 75") |
| 5 | 4 | 10.0 | Floor applies |
| 8 | 0 | 100.0 | Explicit |
| 8 | 2 | 71.4 | Explicit (71.4286 rounded) |
| 8 | 7 | 10.0 | Explicit |
| 3 | 99 | 10.0 | Floor never breached |

**Plus a property test:** for every valid clue count from 3 to 10, the score must **strictly decrease** with each additional clue opened, until the floor is reached, and the floor must be reached only on the final clue. This is what the cap exists to guarantee, and it is the test that would have caught the flat zone.

The start post scores nothing. Maximum possible game score is `scoredPostCount * 100`.

---

## 5. Screens and flows

### 5.1 Home
Two primary buttons: **Create new game** and **Play Nextpost**. Secondary link: **My games** (list of games this user created, showing code, title, post count, status).

**Deleting a game from My games.** Each row carries a delete control, available for drafts and published games alike. Three things must happen together, and none of them is automatic:

- **Subcollections are not deleted with the parent document.** Firestore leaves `posts`, their nested `clues`, and any `sessions` orphaned when the game document goes. They stay in storage, invisible and permanent. Deletion must walk the tree explicitly, deleting clues, then posts, then sessions, then the game.
- **The `gameCodes/{CODE}` entry must go in the same operation** for a published game, or a dead code stays resolvable and a player joining it lands on a game that no longer exists.
- **Confirm before deleting,** naming the game in the dialog. This is irreversible, the control sits in a list row next to a tap-to-open action, and a mis-tap destroys a route someone walked to build. See 14.1 on destructive controls.

Players mid-game on a deleted published game get the "game deleted" error already listed under the join screen in 5.3. Accepted for v1: the creator owns the game and may withdraw it.

Security rules must permit the creator to delete their own games in either status. The M3 draft-only rule is too narrow for this.

### 5.2 Create game

**Name the game first.** Selecting **Create new game** opens a title prompt before the map. The title is **mandatory**, 1 to 60 characters, trimmed. Section 3 carries a `title` field on the game document and My games lists by it, so without this step the field is never populated and a creator with three drafts cannot tell them apart. Naming also gives lazy draft creation a natural trigger.

**Duplicate titles are warned about, not blocked.** Titles are not identifiers; the game code is. Two creators may both have a game called "Fjöruferð", and one creator may legitimately build the same route twice for different groups. But a duplicate among *this creator's own* games defeats the purpose of having titles, so warn on match and let them proceed anyway. Check only against the creator's own games, which are already loaded for My games, and never against a global collection. My games should also show the created date on each row, so rows stay distinguishable whatever the creator names them.

Map screen with the creator's current location centred. Three buttons below the map: **Add**, **Edit**, **Delete**. A list or numbered markers show posts already placed.

**Camera positioning.** Centre on the creator's current location only for the first post of an empty game. From then on the camera stays where the creator left it. Re-centring on the device for every **Add** is wrong whenever the creator is planning rather than walking: placing post 4 near post 3 is the common case, and a creator preparing a countryside route from home would otherwise have to pan a hundred kilometres for every post. Offer a recentre-on-me control for when they do want it, but never force it.

**Opening an existing game** follows the same principle: centre on the last post added, not on the device. A creator resuming a route built elsewhere should land where they left off. Fall back to the device location only when the game has no posts yet.

**Add post**

The post edit flow has four actions and each does something the others do not. The distinction between **Set** and **Save** is the one most easily lost: **Set** changes how the map behaves, **Save** commits the post.

1. Creator taps **Add**. A pin appears at the centre of the map. At this stage the map centre *is* the post location: panning the map moves the post. There is no dragging of the marker itself, since panning under a fixed centre pin is considerably more accurate on a small screen than positioning with a fingertip that covers the target.
2. The creator pans until the pin sits on the intended spot, then taps **Set**. The pin detaches from the centre and locks to that coordinate. From here the map pans freely underneath without moving the post.
3. **Add clue** opens the clue editor. This is deliberately available after **Set** rather than before, because a creator writing "behind the bench facing the water" needs to look around the area, and looking around must not drag the post along.
4. **Save** commits the post and its clues to the draft game and exits post edit mode.
5. **Cancel** discards the post and any clues written for it. If clues have been written, confirm before discarding rather than doing it silently.

Post index 0 takes no clues, so **Add clue** is hidden rather than disabled for the first post.

**Button labels.** Per 14.5 a button says what happens, so **Set** alone is too thin. The verb stays constant and the object varies, so it reads as one action rather than two different buttons:

| Post | Label |
|---|---|
| Index 0 | **Set start location** |
| All others | **Set post location**, or **Set location for post N** if the screen does not otherwise show which post is being placed |

**The label changes once the location is set,** because the state has changed and 14.5 requires the button to say what happens: **Reposition start** and **Reposition post N**. Tapping it must be the exact inverse of **Set**, re-attaching the pin to the map centre so panning moves the post again. It is not a separate mode. Avoid wording that implies the tap begins a drag.

**Reposition centres the camera on the post first.** If the creator has panned away since setting the location, attaching the pin to the current centre would teleport the post to wherever the map happens to sit. So **Reposition** animates the camera to the post's existing coordinate, then attaches the pin. The post does not move until the creator pans deliberately.

No centre crosshair is drawn. The pin marks the map centre whenever it is unset, which is the only state in which the aim point matters, so a separate crosshair would be redundant.

Note the label names the *location*, not the post. The button fixes a coordinate; it does not create or number anything. Avoid "next post" in creator-facing copy entirely: that phrase belongs to the player, who is hunting for a post they cannot see, and reusing it here muddies a term doing real work elsewhere.

**Placement accuracy.** The arrival radius is 25 m, so a post misplaced by 40 m sends players to the wrong bench. At low zoom a fingertip covers far more ground than 25 m, and nothing currently tells the creator that. Two supports:

- **Draw the 25 m radius as a scaled circle around the pin**, so it shrinks and grows with zoom. This makes the precision requirement visible rather than something the creator must imagine, and the same overlay is reusable on the play screen at M5.
- **Require a minimum zoom before Set is available**, around level 17 (roughly building level), or warn if the creator sets a location while zoomed far out.

Pinch-to-zoom gestures stay enabled throughout. Only the +/- control buttons are disabled, per 14.1, because they render bottom-right where the primary action lives.

**Layout constraint:** **Cancel** must not sit adjacent to **Save**, for the same reason **Delete** does not. A mis-tap that discards a post with three written clues has no undo. See section 14.1.

**Clue editor**
- Ordered list of clues with add, edit, reorder, delete.
- Guidance text at the top: first clue is the vaguest, last is a dead giveaway.
- **A visible way to finish.** A top-bar back arrow is not sufficient: it sits outside the thumb zone and creators do not look there, so the screen reads as a dead end once the minimum is met. The bottom third carries the exit.
- **Primary action swaps with progress.** Below the minimum, **Add clue** is primary and **Done** is hidden or disabled, so the layout itself signals what remains. Once the minimum is met they swap: **Done** becomes the primary full-width action and **Add clue** demotes to secondary. The "meets the minimum" confirmation belongs beside **Done**, not floating as a status line with nothing to act on.
- **The editor opens with one empty field already present, but not focused.** Having the field there saves a step; focusing it in code costs a character on Samsung keyboards, per 14.1. The creator taps it. Note the interaction with validation below: an untouched initial field is not an error state, so do not show "Clue 1 is empty" to a creator who has only just arrived. Validation messages appear once the creator has begun entering clues, not on arrival.
- **Add clue is the only way to append.** No IME chaining. Clue fields stay single-line and a newly appended field scrolls into view above the keyboard, but focus stays where the creator puts it.
- **The entry point is labelled "Add clues", plural.** The screen is a list, the minimum is three, and the button opens an editor for all of them. Inside the editor, the button that appends one more field stays singular: **Add clue**.
- **Empty clues are prevented, not warned about.** **Add clue** is disabled while the last field is empty or whitespace-only. **Done** is disabled if any clue is empty, naming which one. Whitespace is trimmed before validating and before saving.
- **The minimum counts only non-empty clues.** Three fields where one is blank does not satisfy it and must not show the confirmation. Otherwise a creator can tap Add clue three times, write nothing, and publish an unsolvable post.
- **Cap clue text at 200 characters,** with a counter appearing around 150. Clues are read on a phone outdoors at a glance, so a pasted paragraph is unusable in practice.

**Where this validation lives.** The 3-clue minimum and the empty-clue rule are the same rule and must not be two validations that can disagree. Put it in `domain/` alongside `ScoreCalculator`, as a pure function over a list of clue strings, because M4 needs it again at publish time and M2's ViewModel is the wrong owner for logic the publish flow also depends on.

**Publish**
- When all posts are placed, the creator taps **Create game**.
- Validation: at least 2 posts total, every post with index >= 1 has >= 3 clues.
- The app generates a unique code, writes `gameCodes/{CODE}`, and sets `status = "published"`.
- A confirmation screen shows the code large and offers a share sheet.

**Editing a published game** is out of scope for v1. Published games are read-only for the creator except for deletion.

### 5.3 Join and play

**Join screen**

Two fields, both required, then a single **Join game** button.

- **Player name.** Free text, 1 to 24 characters, trimmed. This is the only identity the player has, since authentication is anonymous. Prefill it with the last name this device used, stored locally in DataStore, so a returning player joins with two taps. It stays editable: someone handing their phone to a friend needs to change it.
- **Game code.** Uppercase, ambiguity-free alphabet, normalised on input so a player typing lowercase or pasting with spaces still succeeds.
- On submit: look up `gameCodes/{CODE}`, resolve the game, then create or resume `sessions/{uid}` with `displayName` set from the name field.
- Names are **not unique and not verified.** Two players can both be Siggi, and nothing stops a player naming themselves anything at all. That is acceptable for a game played among people who know each other, but it means the name must never be used as an identifier anywhere in code. `playerUid` is the key; `displayName` is decoration.
- Resuming an existing session keeps the original `displayName` unless the player edits it, in which case update the session document.
- Order the fields name-then-code. The code is what the player is holding in their hand and wants to type immediately, so it should be the field that submits.
- Errors: unknown code, game deleted, session already finished (offer restart), empty or whitespace-only name.

Because the name will eventually be visible to the game creator and to other players once a leaderboard exists, treat it as user-generated content that will be displayed to others: escape it on render, cap its display length in the UI, and never interpolate it into a Firestore path.

**Play screen**
- Map showing the player's own location and the current target *only if* the target is post 0. Later targets are never drawn as markers, that would defeat the game.
- Card showing revealed clues for the current target, newest at the bottom.
- **Show next clue** button, disabled once all clues are revealed. Shows the pending penalty before revealing, for example "Opening this drops this post to 75 points."
- Running total score in the top bar.
- Arrival detection: the app polls location and compares against the target radius. When the player is inside the radius, show a success state, award the post score, advance `currentPostIndex`, reset `cluesOpenedForCurrentPost`, and reveal the free clue for the next post.
- Arrival at post 0 runs through the same path but awards nothing. Its success state should read as the game starting rather than a post being scored, for example "You're at the start. Here is your first clue." Before that arrival the clue card is empty and **Show next clue** is hidden, not merely disabled, since there is nothing to hint at yet.
- Manual fallback button **I think I'm here** for cases where GPS drift blocks automatic detection. It runs the same distance check and shows the measured distance if it fails, e.g. "You are about 60 m away."

**Game complete**
- Total score, maximum possible, per-post breakdown showing clues opened, elapsed time.
- Buttons: play again, back to home.

---

## 6. Location handling

- Permission: `ACCESS_FINE_LOCATION`. Request at the point of use, not at launch, with a rationale screen explaining that the game cannot work without it.
- `ACCESS_COARSE_LOCATION` is not sufficient. If the user grants coarse only, block play with a clear message.
- Background location is **not** required. The game is played with the app open. Do not request it, it triggers a Play Store review process for no benefit.
- Keep the screen on during play (`FLAG_KEEP_SCREEN_ON` or Compose equivalent) and handle the app being backgrounded gracefully by pausing location updates.
- Arrival check: use `Location.distanceTo` (great-circle, metres). Default radius 25 m. Reject location fixes with `accuracy > 50 m` rather than triggering a false arrival, and surface a "waiting for a better GPS fix" state.
- Battery: request updates at `PRIORITY_HIGH_ACCURACY` with a 5 second interval only while the play screen is in the foreground.

---

## 7. Game code generation

- 6 characters, alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no I, O, 0, 1 to avoid transcription errors).
- Generation is a pure function; uniqueness is enforced by a Firestore transaction that creates `gameCodes/{CODE}` only if it does not already exist. Retry up to 5 times on collision, then surface an error.
- Codes are case-insensitive on input, normalised to uppercase.

---

## 8. Security rules

Firestore rules should express these intentions:

- Anyone signed in can read `gameCodes/{CODE}` (needed to join).
- A game document is readable by its creator and by anyone with an active session on it.
- Posts and clues are readable by the creator at any time.
- For a player, a **post** document is readable only if `index <= session.currentPostIndex`.
- For a player, a **clue** document is readable only if it belongs to the current target post and `clue.index <= session.cluesOpenedForCurrentPost`.
- A session document is readable and writable only by the player it belongs to, and by the creator for read (future leaderboard).
- Only the creator can write games, posts, and clues, and only while `status == "draft"`.

Rules can enforce the clue gating with a `get()` on the session document. Write the rules alongside the feature, not at the end.

### Widening the rules at M4 — the mistake to avoid

M3's rules are creator-scoped and draft-only. M4 must widen them so a player can read a game they did not create. **The widening must be scoped to holding a session, not to the game being published.**

The tempting rule is "any authenticated user may read a game where `status == 'published'`". It works, it passes every test you would think to write, and it quietly makes every game in the database readable by every user of the app. The code stops being access control and becomes decoration.

The correct condition is that the requester holds a session document on that specific game. A player must join before they can read, and joining requires the code.

Test the negative case explicitly: sign in as a second user, do not join, and confirm that reading a published game by its document id is denied. A rule is only as good as the case you have proved it refuses, and the Rules Playground in the Firebase console does this without writing any app code.

Nextpost has no browse, no search and no public listing by design. If a rule would allow enumerating games, it is wrong regardless of which feature asked for it.

### Known limitation (accept for v1, note it)

To run the arrival check on the device, the client must know the target coordinates, which means a determined player could read them. Rules gating post reads on `currentPostIndex` stops casual snooping of the *whole* route but not the immediate next post. Closing this properly needs a Cloud Function that takes the player's position and answers arrive/not-arrive server-side. That is a good v2 item; it is not worth the complexity for a friendly game between people who know each other.

---

## 9. Localisation

- Default `values/strings.xml` in English, `values-is/strings.xml` in Icelandic.
- No hardcoded strings in composables, everything through `stringResource`.
- Icelandic is the primary audience, so write the Icelandic strings properly rather than machine-translating: *ratleikur* (the activity itself, still the natural Icelandic word for it even though the app is no longer called that), *póstur*, *vísbending*, *stig*, *leikjakóði*.

---

## 10. Milestones

Build in this order. Each milestone should end in a state that runs on a device.

| # | Milestone | Done when |
|---|---|---|
| M0 | Project skeleton | Compose app runs with the identifiers fixed in section 15.1, Firebase connected, anonymous auth works, Maps API key wired via `local.properties`, map renders, `ui/theme/` holds the colour and type tokens from section 14 even if the values are provisional |
| M1 | Location foundations | Permission flow works, map centres on the user, blue dot updates, `ProximityChecker` unit tested |
| M2 | Create game, local only | Add/edit/delete posts held in ViewModel state, markers numbered, clue editor with the 3-clue rule enforced |
| M3 | Persistence | Draft game, posts, and clues round-trip through Firestore; My games list shows drafts |
| M4 | Publish and join | Code generated with collision-safe transaction, join screen resolves a code and creates a session |
| M5 | Play loop | Start post shown, clue reveal, arrival detection, progression to the next post, resume after app restart |
| M6 | Scoring | `ScoreCalculator` wired in with all unit tests green, running total, completion screen with breakdown |
| M7 | Hardening | Security rules deployed and manually tested from a second account, error states, empty states, GPS-accuracy edge cases |
| M8 | Polish | Icelandic strings, app icon, share sheet for the code, screen-on during play |

**Test on a physical Samsung device, not only the emulator.** The emulator is a Pixel running Gboard; this app's market is predominantly Samsung. One input bug has already been traced to that difference alone, after five rounds of fixes aimed at code that was never wrong. Anything involving text entry, keyboards, or GPS behaviour needs confirming on real hardware before a milestone is called done.

---

## 11. Acceptance criteria (starter set)

Written as given/when/then so they can go straight into a test plan.

**AC-1 Minimum clues**
Given a draft game where post 2 has 2 clues, when the creator taps Create game, then publishing is blocked and the app names post 2 as the reason.

**AC-2 Free clue is free**
Given a player arrives at post 1 and post 2 has 5 clues, when the next target loads, then exactly one clue is visible and the projected score for post 2 is 100.

**AC-3 Penalty is proportional**
Given post 2 has 5 clues, when the player opens one extra clue and then reaches post 2, then that post awards 75 points.

**AC-4 Score floor**
Given post 2 has 3 clues, when the player opens both extra clues and reaches post 2, then that post awards 10 points, not 0.

**AC-5 Route is hidden**
Given a player is hunting for post 3, when they inspect the map, then no marker exists for post 3 or any later post.

**AC-6 Arrival requires a good fix**
Given the player is within 25 m of the target but the location accuracy is 80 m, when the arrival check runs, then arrival is not registered and the app shows a waiting-for-signal state.

**AC-7 Resume**
Given a player has completed 2 of 5 posts and opened 1 extra clue for post 3, when the app is killed and reopened, then play resumes at post 3 with that clue still revealed and the score unchanged.

**AC-9 Start post gates the first clue**
Given a player has just joined a game and is 400 m from the start post, when they open the play screen, then the start post is marked on the map, no clue text is shown, and the Show next clue button is not available.

**AC-10 Start post awards nothing**
Given a player walks into the 25 m radius of the start post, when arrival registers, then the running total remains 0, the free clue for post 1 appears, and the start post marker is removed from the map.

**AC-11 Name is required to join**
Given a player enters a valid code but leaves the name field empty or types only spaces, when they tap Join game, then joining is blocked and the name field shows the reason.

**AC-12 Name is remembered**
Given a player joined a previous game as "Siggi", when they open the join screen again, then the name field is prefilled with "Siggi" and remains editable.

**AC-13 Set detaches the pin from the map centre**
Given a creator has tapped Add and the pin is tracking the map centre, when they tap Set and then pan the map, then the pin stays at the coordinate it held when Set was tapped and does not follow the centre.

**AC-14 Cancel protects written work**
Given a creator has added two clues to a post that has not been saved, when they tap Cancel, then a confirmation appears before anything is discarded.

**AC-15 Blank clues do not satisfy the minimum**
Given a post has three clue fields and one contains only whitespace, when the creator views the clue editor, then the minimum is reported as unmet, Done is disabled, and the empty clue is named.

**AC-16 The clue editor has a visible exit**
Given a post has three valid clues, when the creator views the clue editor, then a Done button is the primary action in the bottom third and Add clue is secondary.

**AC-17 The arrival radius is visible during placement**
Given a creator is placing a post, when the pin is shown, then a 25 m radius circle is drawn to scale around it and resizes with zoom.

**AC-18 A game must be named**
Given a creator selects Create new game, when the title prompt appears, then the map is not reachable until a non-empty title is entered, and that title appears in My games.

**AC-19 The camera holds position between posts**
Given a creator has set post 1 and pans the map two kilometres away, when they tap Add for post 2, then the camera stays where they left it and does not return to the device location.

**AC-20 Reposition re-attaches the pin**
Given a post location has been set, when the creator taps Reposition and pans the map, then the pin follows the map centre again and the centre crosshair is visible.

**AC-21 Deletion leaves nothing behind**
Given a published game with three posts and their clues, when the creator deletes it and confirms, then the game document, all posts, all clues, all sessions and the gameCodes entry are gone, verified in the Firestore console.

**AC-22 Reposition does not teleport the post**
Given a post location has been set and the creator has panned two kilometres away, when they tap Reposition, then the camera returns to the post's coordinate and the post has not moved.

**AC-23 A published game is not readable without joining**
Given user B has never joined user A's published game, when user B attempts to read that game document, its posts or its clues directly by id, then every read is denied. Verified in the Rules Playground, not only through the app.

**AC-8 Unknown code**
Given a player enters a code that does not exist, when they submit, then an error explains the code was not found and the input is preserved.

---

## 12. Decisions to confirm before M2

These are set to defaults in this document. Confirm or change them.

1. **Route order.** Assumed strictly linear, posts must be found in sequence. Alternative: free order, find any remaining post. Linear is simpler and matches the clue-chain design.
2. **Arrival radius.** *(Confirmed: 25 m.)* Fixed per game, stored as `defaultRadiusMeters` on the game and copied to each post at creation so it can become per-post configurable later without a migration. Urban GPS in Reykjavík is usually good to 5–10 m; 25 m gives headroom without making posts trivially findable.
3. **Auth.** Assumed anonymous sign-in with no account creation, with the player supplying a free-text name when joining, as specified in section 5.3. Adding real accounts later is an upgrade path on the same anonymous UID, not a rewrite, and a verified account name would then replace the free-text one.
4. **Multiple players per game.** The model supports many sessions per game, but v1 shows no leaderboard, each player only sees their own score. Confirm this is acceptable for the first release.
5. **Photo clues.** Out of scope for v1. Adding them later means Firebase Storage plus a `imageUrl` field on the clue document.
6. **Time.** Elapsed time is recorded but does not affect score. A time bonus is a plausible v2 feature.
7. **Offline.** Assumed online-only. Firestore's offline cache will cover brief signal drops, but a game in the highlands would need real offline support and cached map tiles.

---

## 13. Out of scope for v1

Photo clues and server-side arrival validation are excluded partly on cost grounds, not only on complexity. See section 15.2.


iOS client, editing published games, photo and audio clues, leaderboards, live player tracking for the creator, teams, offline maps, deep-link joining, server-side arrival validation, push notifications.

---

## 14. Look and feel

Split into two layers. **14.1 and 14.2 are binding from M1** because they shape screen structure. **14.3 is a proposal** that can be swapped at any point up to M8 without touching layout code, provided 14.4 is respected.

### 14.1 The operating context drives the layout

Nextpost is used outdoors, while walking, in daylight, one-handed, often in cold or wet weather, on a phone that may be behind a glove or a wet screen. Every layout decision follows from that:

- **Thumb zone.** Every action a player takes while moving sits in the bottom third of the screen. Nothing functional goes in the top corners. The top bar is for glanceable read-only state only: score, post counter, GPS quality.
- **Tap targets.** 48 dp minimum everywhere, 56 dp for any primary action on the play screen. Cold fingers and gloves are imprecise.
- **Separate the costly action from the frequent one.** **Show next clue** permanently costs points and **I think I'm here** will be tapped repeatedly. They must not be adjacent. Put the arrival check as the wide primary button at the bottom and the clue reveal inside the clue sheet, and require a confirmation on the clue reveal that names the cost: "Open clue 3 of 5? This post drops to 50 points."
- **Separate destructive from constructive.** In the post editor, **Delete** never sits next to **Save**.
- **The keyboard must never cover the primary action.** Any screen with a text field applies IME padding so the action button rises above the keyboard rather than sitting behind it. Keyboard-first screens are laid out top-aligned, not bottom-aligned: a field placed low on the screen is a field the keyboard will hide the moment it is tapped.
- **Text input carries the right IME configuration.** Sentence capitalisation on every field a person writes prose into, so the first letter is capitalised without reaching for shift.
- **Do not set focus programmatically.** The creator taps the field they want. Programmatic focus was tried and abandoned: on Samsung's keyboard, setting focus in code restarts the input connection and drops the character being composed, so the first character typed into a newly focused field is lost. The committed text survives in the ViewModel while the field's own buffer resets, which makes the symptom look like a state bug and is not one. It does not reproduce on Gboard, so an emulator will show it working. Samsung is the dominant Android brand in this app's market, so "works on Pixel" is not a passing grade. Scrolling a newly added field into view is fine and unrelated; it is focus specifically that must stay manual.
- **Glanceability over density.** A player looks at the screen for two seconds at a time. The current clue and the distance-to-target are the only things that need to be readable at arm's length in sun.
- **Light theme is the default, not dark.** Dark UI is less readable in direct sunlight even at full brightness. Follow the hiking-app convention: light surfaces, near-black text, and a single saturated accent. Offer dark mode, but do not make it default, and remember the map style must switch with it.
- **Screen-on and battery.** Keep the screen awake during play; that makes the high-contrast light theme a battery cost worth accepting for a game measured in tens of minutes.

### 14.2 Screen skeletons

```
PLAY SCREEN                          CREATE SCREEN
┌────────────────────────────┐       ┌────────────────────────────┐
│ ‹  cairn ▲▲△△△   142 pts   │ ← RO  │ ‹  New game        4 posts │
├────────────────────────────┤       ├────────────────────────────┤
│                            │       │                            │
│           MAP              │       │           MAP              │
│      (own position,        │       │      (numbered posts,      │
│    target hidden unless    │       │     fixed centre pin)      │
│         post 0)            │       │             ⊕              │
│                            │       │                            │
├────────────────────────────┤       ├────────────────────────────┤
│ ▲ clue sheet (draggable)   │       │  [ Add ] [ Edit ] [Delete] │
│   "Where the moss meets…"  │       │                            │
│   [ Show next clue ]       │       │  [    Create game     ]    │
├────────────────────────────┤       └────────────────────────────┘
│  [   I think I'm here   ]  │ ← 56dp
└────────────────────────────┘
```

The clue sheet is a bottom sheet so a player can drag it up to reread earlier clues and drag it down to see more map. Collapsed state shows the most recent clue only.

### 14.3 Starting design direction (proposal)

Grounded in Icelandic route-finding rather than generic outdoor-app styling.

**Signature element: the varða.** Icelandic stone cairns have marked routes across this country for a thousand years, which is exactly what this app does. Use a stacked-cairn glyph as the progress indicator instead of "3 of 7": one stone per post found, stacking upward in the top bar, standing complete on the finish screen. It is the one memorable thing in the app, so everything around it stays plain.

**Palette** — five values, used with discipline:

| Token | Hex | Role |
|---|---|---|
| `Basalt` | `#23262B` | Body text, cairn stones |
| `Jökull` | `#F7F8F6` | Surfaces, default background |
| `Mosi` | `#7C8C6F` | Secondary text, inactive markers, dividers on maps |
| `Aska` | `#C7CBC4` | Borders, disabled states, unstacked cairn stones |
| `Flagg` | `#E8590C` | The one accent |

`Flagg` is the orange of an orienteering control flag, and it is reserved for exactly two things: the current target and the primary action. If it appears anywhere else it stops meaning "this is where you're going." Note that this is a hot, saturated orange, deliberately not a soft terracotta.

**Type** — three roles:

- Display (screen titles, the game code, the final score): a characterful grotesque such as **Space Grotesk**.
- Body (clue text above all): **Atkinson Hyperlegible**, designed for legibility at a glance and unusually good in poor conditions. Clue text is the most important text in the app and deserves the most readable face.
- Data (distances, points, the code when transcribed): a mono such as **IBM Plex Mono**. Codes get read aloud and typed in by someone else, and mono removes the ambiguity that the code alphabet already guards against.

**Verify Icelandic glyph coverage before committing to any face.** The app needs Þ þ Ð ð Æ æ Ö ö and the acute vowels á é í ó ú ý. Plenty of otherwise good display fonts ship without them, and a missing eth in the middle of a clue is a bug the designer never sees but every user does. Load via Downloadable Fonts, with a bundled fallback.

**Motion:** almost none. One place only, the stone dropping onto the cairn when a post is found. That moment is the reward; everything else is a walking app that should get out of the way.

### 14.4 The discipline that keeps 14.3 cheap to change

This is the part that actually matters and it costs nothing to follow from day one:

- Define a Material 3 `ColorScheme` and `Typography` once in `ui/theme/`. **No composable ever names a colour or a font.** Every reference goes through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*`. No `Color(0xFF...)` outside the theme package.
- Map the five palette tokens onto M3 roles rather than inventing a parallel system: `Flagg` → `primary`, `Basalt` → `onSurface`, `Jökull` → `surface`, and so on. Extend with a small custom token class only if M3's roles genuinely do not cover a case.
- Spacing on a 4 dp grid, defined as named constants, never as literals in layout code.
- Map styling lives in a JSON style resource, not in code.

Follow that and swapping the whole visual identity later is an afternoon in one directory. Ignore it and it is a week of grep.

### 14.5 Copy rules

- Buttons say what happens: **Create game**, not Submit. The word on the button is the word in the confirmation that follows.
- Errors state what happened and what to do: "No game found with that code. Check it with whoever set up the game." Never an apology, never vague.
- Empty and waiting states are instructions: "Waiting for a better GPS signal. Move away from buildings if you can."
- Sentence case throughout, both languages.

---

## 15. Repository conventions

- `.gitignore` must exclude `local.properties` and any API key file. The Maps key goes in `local.properties` and is injected with the Secrets Gradle Plugin, never committed.
- `google-services.json` is required for the build. Restrict the Firebase API key by package name and SHA-1 in the Google Cloud console rather than relying on secrecy.
- Unit tests live in `src/test`, no instrumentation needed for `domain/`.
- Conventional commit messages, one milestone per branch.

### 15.1 Naming and future rebrands

The name may change. Structure the project so that it costs a string edit rather than a refactor.

**Confirmed identifiers — create the project with these, do not accept Firebase or Android Studio defaults:**

| Identifier | Value | Mutable? |
|---|---|---|
| `applicationId` / `namespace` | `is.siggi.nextpost` | Until first Play Store release, then permanent |
| Firebase project ID | `nextpost-is` | Permanent from creation |
| Firebase project display name | Nextpost | Editable any time |
| Firestore database ID | `(default)` | Leave as is |
| Firestore location | `europe-west2` | Permanent from creation |
| `app_name` string resource | Nextpost | Editable any time, both locales |

The Firebase project ID appears publicly in `nextpost-is.web.app`, the Storage bucket name, and Auth handler URLs such as password-reset links, so it is worth being deliberate about. `nextpost-is` reads cleanly in all three, and the `-is` suffix is short enough to stay unobtrusive while doubling as the Iceland country code.

Note that `nextpost-is` is 11 characters, comfortably inside the 6 to 30 character limit for Firebase project IDs, and uses only lowercase letters and a hyphen as required. If it turns out to be taken, Firebase will suggest appending a random suffix; prefer picking a deliberate alternative such as `nextpost-app` over accepting `nextpost-is-4f2c`, since this string is permanent.

- **The display name lives only in `strings.xml` as `app_name`,** in both `values/` and `values-is/`. No composable, manifest label, or notification title ever contains the literal word Nextpost. Sentences that mention the app compose it in: `stringResource(R.string.welcome_message, stringResource(R.string.app_name))`.
- **No product name in class, file, or package member names.** `GameRepository`, `AppTheme`, `PlayViewModel`. Never `NextpostRepository` or `NextpostTheme`.
- **`applicationId` is an identifier, not a brand, and it is permanent from the first Play Store release.** Google keys the store listing to it and there is no rename path; changing it after publication means a new listing with zero installs and reviews. Choose it once, before M4, and accept that it may outlive the brand. This is normal and mostly invisible to users.
- **If the `applicationId` must change before publication,** the sequence is: refactor the package in Android Studio, update `namespace` and `applicationId` in `build.gradle.kts`, register a new Android app inside the *same* Firebase project, replace `google-services.json`, and update the package-name and SHA-1 restrictions on the Maps API key. Firestore data is scoped to the Firebase project, not the Android app registration, so nothing is lost.
- **Verify availability before creating anything.** Check that `nextpost-is` is free as a Firebase project ID and that no existing Play Store listing conflicts with the Nextpost name. Nextpost is a generic English compound and collides most often with social-media scheduling tools, so a store-name clash is plausible even though the project ID should be clear.
### 15.2 Billing and cost constraints

**Verified August 2026. Both Firebase and Google Maps changed their free-tier terms in 2025 and 2026, so re-check anything here that would cost money before relying on it.**

The whole of v1, M0 through M8, runs at zero cost. Keep it that way by treating the following as a hard architectural constraint rather than a budget note.

**Firebase Spark (free) covers everything in this spec.** Firestore allows 1 GiB stored, 50,000 reads, 20,000 writes and 20,000 deletes per day, and 10 GiB monthly egress. Anonymous and standard authentication are free regardless of volume. A game with tens of players generates a few hundred operations per playthrough, which is three orders of magnitude below the ceiling.

**Do not introduce these without an explicit decision, because each one requires the paid Blaze plan:**

- **Cloud Storage.** Since February 2026 a linked billing account is required to provision a bucket, even inside the free tier. This is why photo clues are out of scope; adding them is a billing decision, not just a feature.
- **Cloud Functions.** Needed for server-side arrival validation, which section 8 lists as the proper fix for the coordinate-visibility limitation. Sources disagree on what Spark currently permits for Functions, so verify against the live pricing page at the point of use.
- **Named (non-default) Firestore databases.** The free quota applies to one default database per project only. Another reason section 15.1 pins the database ID to `(default)`.

**Google Maps is the one place a payment method is unavoidable.** Mobile usage of the Maps SDK for Android is unlimited and free, but a Maps Platform key without an attached billing account is throttled to one request per day, which looks exactly like a broken key. A card is therefore required even though the map costs nothing.

**Attaching that billing account is what moves a Firebase project from Spark to Blaze.** Blaze keeps the same free quotas but removes the hard stop, so a runaway snapshot listener bills instead of halting. Two acceptable configurations:

1. *Preferred during development.* Keep the Maps API key in a separate Google Cloud project that has billing enabled, and leave `nextpost-is` on Spark with no card. Firestore then hard-stops on quota exhaustion, which is the behaviour you want while writing listener code.
2. *Simpler.* Accept Blaze on the single project and set a Cloud Billing budget alert at a low threshold, for example EUR 5. Blaze has no hard spending cap, so the alert is the only safety net.

**Cost-relevant coding rules, applicable from M3 onward:**

- Prefer one-shot `get()` over `addSnapshotListener` unless the screen genuinely needs live updates. Nothing in v1 does; a player's own session is only written by that player.
- Never attach a listener to a whole collection where a document read would do. Reads are billed per document returned, including documents read to satisfy a query.
- Detach every listener in `onStop`. A listener leaked across configuration changes is the single most common way a hobby Firestore project generates surprising traffic.
- Storing each clue as its own document is a security-rules requirement from section 8, not an oversight. It costs a handful of extra reads per post, which is irrelevant at this scale.
