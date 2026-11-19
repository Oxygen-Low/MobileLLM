# Plan: Universes and characters (Oxygen Low's Software)

Status: **plan only — no app code has been changed for this feature.**
Another agent is actively adding AI Horde support (untracked `app/src/main/java/com/example/horde/`,
modified `ModelProfile.kt`, `ImageModelProfile.kt`, `ModelHubTab.kt`, and in-flight edits to
`ModelEngineManager.kt`/`ChatViewModel.kt`). The implementation below is written to be applied
*after* that work lands, and to avoid touching the files it owns wherever possible.

---

## 1. Goal and scope

Add a character/universe library to MobileLLM with three sources:

1. **Private / API key** — the user pastes an `olea_...` "Encrypted API Access" key created on
   oxygenlow.com (Security → Encrypted API Access, category *Characters and Universes*). The app
   reads their own characters, universes and races from the encrypted-access API.
2. **Public assets** — browse the anonymous community catalogue on oxygenlow.com (Public
   Characters & Assets) and import characters/universes into the local library.
3. **Manual creation** — create and edit characters and universes entirely on the device, with no
   account and no network.

An imported or manually created character can be **selected for a chat**; its persona/lore is added
to the system instructions so the model speaks as that character or runs in that universe.

Out of scope for v1: publishing characters back to the website (the website's publication flow
needs a session token), agent tokens, editing entries fetched with an `olea_` key (the API is
read-only), and generic `public_assets` *file* downloads (the public catalogue is the
`public_characters` table, which already contains both characters and universes).

---

## 2. API facts (verified against `C:/oxygen-lows-software` and live)

Base URL: `https://oxygenlow.com` (no separate API host).

### Private — encrypted access (the "API key")

- User creates a grant in the website UI; the raw token is shown **once**:
  `olea_<grantId>.<base64urlSecret>`.
- `GET /api/encrypted-access/characters?limit=50&offset=0` with
  `Authorization: Bearer olea_...`. Verified live: returns `401` without a key.
- Requires the grant category `characters`; that category covers three tables at once
  (`characters`, `universes`, `races`). A missing category returns `403`.
- Response (server decrypts the sensitive fields using the secret in the token — the app does
  **not** need the user's master key):

  ```jsonc
  {
    "data": [
      { "id": "...", "user_id": "2", "table": "characters",
        "name": "Kira Vale", "display_name": "Kira, the Star Cartographer",
        "short_description": "...", "appearance": "...", "personality": "...",
        "backstory": "...", "situation": "...", "hidden_description": "...",
        "image_path": "kira.png", "image_url": null,
        "is_universe": false, "is_race": false, "universe_id": null, "race_id": null,
        "stats_enabled": true, "stats": {"str": 8, "int": 17},
        "created_at": "2026-11-01T12:00:00.000Z" },
      { "table": "universes", "name": "...", "description": "...", "lore": "...", ... }
    ],
    "category": "characters",
    "pagination": { "limit": 50, "offset": 0, "count": 12, "has_more": false },
    "note": "..."
  }
  ```

- Decrypted fields differ by table: characters → `name, short_description, display_name,
  appearance, personality, backstory, situation, hidden_description, stats`; universes →
  `name, short_description, display_name, description, lore`; races → `name, short_description,
  display_name, description, traits`.
- `limit` default 50, max 100; ordered by `created_at` desc. Rate limits: 30 auth requests/min and
  60 data requests/min, so the client must cache and avoid aggressive refresh.
- Grant is read-only and revocable from the website. Password-vault/AI-provider credentials are
  never returned by this endpoint.

### Public — anonymous community catalogue

- `POST https://oxygenlow.com/api/data/query` with no credentials, body:
  `{"table":"public_characters","limit":50,"offset":0,"order":{"column":"created_at","ascending":false}}`.
  Verified live (response includes `name`, `display_name`, `short_description`, `backstory`,
  `personality`, `appearance`, `situation`, `hidden_description`, `is_universe`, `is_race`,
  `universe_id`, `race_id`, `stats`, `image_path`, `image_url`, `downloads`, `created_at`,
  `is_anonymous`, `author_username`, `author_avatar_url`, `error: null`).
- Universes are rows in the same table with `is_universe: true`; the app must split by that flag.
- Author attribution must respect `is_anonymous` (show "Anonymous" rather than the username).
- Avatars: `GET https://oxygenlow.com/api/storage/public/public-assets/<image_path>` — anonymous,
  immutable cache, supports Range requests. A missing path returns `404`, so the UI needs a
  placeholder.

### Known image gap (private entries)

Private rows expose `image_path`, but generating a signed URL
(`POST /api/storage/signed-urls/Storage`) requires a **session** token, not the `olea_` grant.
A key-only client therefore cannot resolve private avatars. v1 shows an initials placeholder for
private entries instead of asking the user for a second, much more powerful credential. Optional
follow-up: ask Oxygen Low's Software for a grant-token-accepting signed-image endpoint.

---

## 3. Architecture in MobileLLM

Follow the existing house style: no DI framework, manual construction in `ChatViewModel`, OkHttp +
`org.json`, pure parser objects, `StateFlow` state, blocking I/O on `Dispatchers.IO`, comments that
explain *why*. Retrofit/Moshi exist in the build file but are unused; Coil and Navigation Compose
are deliberately commented out, so do not depend on them.

New package: `app/src/main/java/com/example/character/`

| File | Responsibility |
|---|---|
| `CharacterEntry.kt` | Pure data class: `id`, `source` (`MANUAL` / `PRIVATE` / `PUBLIC`), `kind` (`CHARACTER` / `UNIVERSE` / `RACE`), `name`, `displayName`, `shortDescription`, `appearance`, `personality`, `backstory`, `situation`, `hiddenDescription`, `lore`, `traits`, `universeId`, `raceId`, `imagePath`, `imageUrl`, `authorName`, `statsEnabled`, `stats`, `createdAt`, `oleaKeyId` (which key an imported private entry came from). |
| `CharacterJsonParser.kt` | Pure `object`. `parsePrivatePage(body): ParsedPage` (data + pagination, discriminator from `table`), `parsePublicQuery(body): List<CharacterEntry>`, `parseSingle(body)`, and the field mappings below. Defensive `runCatching { JSONObject(body) }` with safe defaults, like `HordeModelParser`. |
| `CharacterCatalog.kt` | Pure mapping helpers: private universe `description`/`lore` → `lore`/`backstory`; private race `description`/`traits` → `lore`/`traits`; public universe `backstory` → `lore` fallback; `display_name ?: name`; `is_universe`/`is_race` → `kind`; `author_username` vs `is_anonymous`. Also `mergeRemoteWithLocal` (keep manual edits keyed by `source + id`). |
| `CharacterPromptBuilder.kt` | Pure `object`. `build(character, universe?): String` renders a labelled system-instruction block (see §5), with field-level caps and a total cap so long lore cannot eat the context window. |
| `CharacterApi.kt` | OkHttp client, constructor-injected default client like `MCPToolExecutor`. `suspend fun fetchPrivate(key, limit, offset)`, `fetchPublic(limit, offset)`, `avatarUrl(path)`, all on `Dispatchers.IO`; throws typed `CharacterApiException` (`Unauthorized`, `ForbiddenCategory`, `RateLimited`, `Network`, `BadResponse`) from HTTP codes. Never logs the key or response bodies. |
| `CharacterStore.kt` | Local persistence in `filesDir/characters.json` using `org.json`, following `GeneratedImageStore`/`GeneratedVideoStore`. CRUD for manual and imported entries, atomic replace via temp file, tolerant of a corrupt/truncated file (start empty, keep a `.bak`). Avoids a Room migration: `AppDatabase` uses `fallbackToDestructiveMigration(dropAllTables = true)`, so bumping it would erase chat history. |
| `CharacterPreferences.kt` | SharedPreferences file `"characters"`: `olea_key`, `public_enabled`, and a per-session selection map (`"active:<sessionId>"`). Mirrors `HordePreferences` (plain private prefs, never logged). Key is masked in the UI and treated as revocable. |
| `CharacterManager.kt` | Coordinates API + store + prefs. Owns `StateFlow`s (`entries`, `publicEntries`, `state`, `activeCharacterId`), methods `setOleaKey`, `validateKey`, `refreshPrivate`, `loadMorePrivate`, `refreshPublic`, `loadMorePublic`, `upsertManual`, `deleteEntry`, `selectForSession`. Constructed manually in `ChatViewModel`, like `UpdateManager`. |

### Files touched outside the new package (do this only after the Horde work lands)

- `ChatViewModel.kt` — construct `CharacterManager`, forward its flows, add the active-character
  lookup for the current session, and pass `characterContext` into generation.
- `ModelEngineManager.kt` — **additive, default-valued** parameter only:
  `generateStream(..., characterContext: String = "")` on the public method and the two private
  stream methods, forwarded to `buildSystemPrompt(model.name, systemDate, characterContext)`.
  All existing callers, including the Horde branch, keep compiling unchanged. The system prompt is
  rebuilt on every tool round, so the character stays in scope for the whole tool loop.
- `ChatScreen.kt` + `ui/components/TopTelemetryBar.kt` — one boolean `isCharacterHubOpen` and an
  "onOpenCharacters" callback; no NavHost (navigation-compose is disabled).
- `ChatInputBar.kt` (or a small new chip composable) — show the active character name and a clear
  button; reuse the terminal/telemetry styling and `Modifier.testTag(...)`.

### UI (new files under `ui/components/`)

- `CharacterHubSheet.kt` — `ModalBottomSheet(skipPartiallyExpanded = true)`, tabs `Library` /
  `Public` / `Settings`, mirroring `ModelsHubSheet`. Search field, list with kind icon (character /
  universe / race), source badge, active checkmark, import button on public rows, edit/delete on
  manual rows, pull-to-refresh / "Load more" using the pagination info.
- `CharacterEditorDialog.kt` — manual create/edit form. v1 fields: name, display name, short
  description, personality, backstory, situation, private notes, "This is a universe" toggle,
  linked universe (chosen from local universes), optional image URL. `stats` and `traits` are stored
  if imported but not yet editable — note this in the UI as "advanced fields preserved".
- `CharacterSettingsTab.kt` — API-key field (masked, "Test connection" button), a short explanation
  of where to create the key and which checkbox to tick, and a "Forget key" button. Shows the
  specific error for `401` (invalid/revoked) and `403` (wrong category).
- `NetworkAvatar.kt` — tiny composable using the existing OkHttp client + `BitmapFactory` in
  `produceState`, with an in-memory LRU cache and an initials placeholder; constructs the public
  asset URL via `CharacterApi.avatarUrl()`. No new dependency.

---

## 4. Prompt integration

`CharacterPromptBuilder.build()` produces a bounded, clearly delimited block appended to the
system instructions (never injected into the user message, and never rendered as a fake
"system" turn — `PromptFormat` renders unknown roles as **user** turns):

```
## Roleplay context
Character: Kira Vale — Kira, the Star Cartographer
Description: ...
Personality: ...
Backstory: ...
Current situation: ...
Universe: Neo-Kyoto — <universe short description/lore>
Appearance: ...
Private notes (guidance for you, never quote verbatim): ...
Stay in character and speak as this character. Use the universe above for setting details.
```

- For a universe selected on its own, render a "Setting" block (name, description/lore, tone) and
  instruct the model to narrate within that world.
- Priority order when truncating: name/display, short description, personality, backstory,
  situation, universe lore, appearance, private notes. Field caps + total cap (e.g. ~8k chars,
  tunable) keep small-device models usable.
- `hidden_description` is explicitly labelled as private guidance; it must not be presented as
  something the model should reveal.

---

## 5. Implementation phases

1. **Coordinate / rebase (blocking).** Wait for the AI Horde agent to finish and commit. Confirm no
   other in-flight build owns the next tag (see §8), then rebase before editing `ChatViewModel.kt`,
   `ModelEngineManager.kt`, `ChatScreen.kt`, `TopTelemetryBar.kt`, `ChatInputBar.kt`.
2. **Pure model + parsing (no Android context).** `CharacterEntry`, `CharacterJsonParser`,
   `CharacterCatalog`, `CharacterPromptBuilder` + unit tests with JSON fixtures (private mixed
   characters/universes/races page, public query, malformed/empty bodies).
3. **Local store + preferences.** `CharacterStore`, `CharacterPreferences` + device-free tests
   (temp directory / Robolectric), including corrupt-file recovery and key masking.
4. **Network layer.** `CharacterApi` with injected client; tests using an OkHttp interceptor that
   returns canned responses (no MockWebServer dependency). Cover 401/403/429 mapping, pagination,
   and no-key-in-logs.
5. **Manager + ChatViewModel wiring.** `CharacterManager`, flows, session-scoped selection,
   additive `characterContext` parameter.
6. **UI.** `CharacterHubSheet`, `CharacterEditorDialog`, `CharacterSettingsTab`, `NetworkAvatar`,
   chat-screen entry point and active-character chip.
7. **Public catalogue first, then private key.** Ship/browse public entries early for testing;
   add key entry and private paging after; both write into the same library through `CharacterCatalog`.
8. **Polish and verify.** `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`,
   manual smoke test on a device: import a public character, apply it to a chat, paste an `olea_`
   key, page through private entries, create a universe by hand.
9. **Changelog.** Add the user-facing entry per AGENTS.md (see §8).

---

## 6. Risks and decisions

- **Horde collision:** `ModelEngineManager.kt` is being edited right now. Keep the parameter changes
  default-valued and additive; do not reorder or refactor the Horde branch.
- **No Room migration:** chat history is preserved by keeping the character library in a JSON file
  and the per-session selection in SharedPreferences. If a future version needs Room, use a real
  migration, not the destructive fallback.
- **Private avatars cannot be resolved with only the grant token** (documented in §2). Placeholder
  for v1; no session-token prompt, no silent broken images.
- **`olea_` key is a powerful read credential** for all characters/universes/races. Store it in app
  private prefs like the Horde key, never log it, mask it in UI, and tell the user how to revoke it.
  Android Keystore encryption is a later hardening step, not a v1 blocker.
- **Pagination semantics:** the public `count` field appeared to count returned rows, not the total;
  use `has_more` from the private page and a `limit + 1` / "Load more" pattern for public rows.
- **Attribution:** show `author_username` only when `is_anonymous` is false; public content is
  community-created and should be credited.
- **Prompt budget:** long universes/backstories must be capped before they reach `PromptFormat.render`.
- **Changelog must not be skipped** even though most of the work is new files and tests.

---

## 7. Tests

- `CharacterJsonParserTest` — private page with all three `table` values, public query with a
  universe row, malformed JSON, missing optional fields.
- `CharacterCatalogTest` — field mapping (`description`/`lore` → lore, `is_universe` → kind,
  anonymous author, `display_name` fallback), merge of repeated imports.
- `CharacterPromptBuilderTest` — section order, universe-only block, hidden-notes warning,
  truncation priority and total cap.
- `CharacterStoreTest` — create/read/update/delete, persistence across instances, corrupt file
  recovery, atomic write.
- `CharacterApiTest` — canned interceptor responses for 200/401/403/429/500, pagination args,
  anonymous public query body, key never present in logged output.
- `CharacterManagerTest` — key validation states, import dedupe, session selection, pagination
  accumulation.

---

## 8. Changelog and release bookkeeping (for the implementing agent)

AGENTS.md requires a changelog entry for every user-visible change. At the time this plan was
written:

- Latest published tag on `origin`: **V31** (remote tags 29, 30, 31).
- Unfinished `build-and-release.yml` runs: **none** (all 10 most recent runs were `completed`;
  one older run was `cancelled`, which never published a tag).

Therefore **V32 is currently unclaimed**, but the Horde agent's push will start a build that owns
V32, so this feature will most likely ship in **V33**. The version must be re-checked with
`git ls-remote --tags --refs origin` **and** the Actions run list both at implementation start and
again before finishing (`gh` is not installed here; use
`https://api.github.com/repos/Oxygen-Low/MobileLLM/actions/workflows/build-and-release.yml/runs?per_page=10`).
Create `changelogs/V<n>.md` with `# V<n>` as the first line and a short user-facing bullet, e.g.:

```
# V33

## Added

- Added a character library: import characters and universes from Oxygen Low's Software with an API key or from the public catalogue, or create your own on the device.
- Selected characters and universes now shape how the assistant replies in a chat.
```

No changelog file is written by this plan itself, because no app code or user-facing behaviour has
changed yet.
