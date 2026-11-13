# AGENTS.md

Instructions for every agent (human or AI) working on MobileLLM.

## What this project is

MobileLLM is an Android app that runs LLMs completely on the device. The models
are llama.cpp builds, the inference engine is native code under
`app/src/main/cpp`, and the user interface is Jetpack Compose.

- `app/src/main/java/com/example/ui` - the chat screen and its view model
- `app/src/main/java/com/example/model` - model loading, prompting, streaming
- `app/src/main/java/com/example/update` - the in app updater
- `app/src/main/java/com/example/changelog` - the changelog reader
- `changelogs/` - one markdown file per released version

## Changelogs are not optional

**Every change you make has to be described in the changelog of the version it
will be released in.** The app shows those notes to the user after the update
and the file is the release notes of the GitHub release. A change that is not
written down does not exist for the users.

A version that is released without a changelog file is not a broken release:
the build is published anyway and the notes of that version say that there are
no changelogs for it. Writing the file is still the rule for every change you
make.

**Rule for agents: write the changelog file as part of the work, not as an
optional extra.** Whenever you finish a change that a user would notice, create
or update the markdown file in `changelogs/` for the version under development
and put the entry there. The only reason to skip it is that you were explicitly
told not to write a changelog for that change. If nobody said that, then the
changelog entry is part of the task, together with the code change, and a
reviewer will send the work back without it.

Do this even when:

- the change looks like a small fix, a refactor or a cleanup,
- the change touches only tests, build files or the release workflow,
- several changes land in the same version, they all belong in the same file,
- the version file does not exist yet, create it with the `# V<n>` heading.

### Which version is the right one

The version of the app is the git tag `V<n>`, where `n` is the
`versionCode` of the build. Releases are cut from `main` and the workflow
picks the next number from the existing tags, so the version that is currently
under development is always the highest tag plus one.

```bash
# The version that is being worked on right now.
latest=$(git tag -l 'V[0-9]*' | sed -E 's/^V([0-9]+)$/\1/' \
  | grep -E '^[0-9]+$' | sort -n | tail -n 1)
echo $(( ${latest:-0} + 1 ))
```

With the tags `V1` to `V11` the version under development is `V12`, so the
entry goes into `changelogs/V12.md`.

Rules:

1. Add your entry to `changelogs/V<next version>.md`. Create that file with
   the heading `# V<next version>` when it does not exist yet.
2. Never move your entry to another version file. If a `V<n>.md` file has a
   matching git tag, that version is already released and its notes are shown
   to everyone who updated to it. Fixing a released version means describing
   the fix in the *new* version file.
3. If your work is big enough to deserve its own release, say so in your
   summary. The number is still picked by the release workflow, so the file
   name always follows the tags.

### How a changelog file looks

```markdown
# V12

- Added on-device speech to text and text to speech.
- Fixed the update check reporting an error on every poll.

## Fixed

- The transcript no longer scrolls away while you read older messages.
```

- The first line is always `# V<n>` and nothing else.
- One bullet per change, written as a full sentence.
- Write for the user, not for the other developers. Describe what changed for
  the person using the app, not which class you touched.
- Group the bullets under `## Added`, `## Changed` and `## Fixed` when a
  version has more than a couple of changes. A plain list of bullets is fine
  too.
- No commit hashes, no file names, no ticket numbers.
- The app renders headings, bullets and plain paragraphs. `**bold**` and
  `` `code` `` work inline.

## What the user sees

After an update the app asks whether the user wants to read what changed. If
they say yes, it shows the changelog of every version between the version they
were using and the version they are on now, so a user that skipped three
releases sees all three files. A version that was released without a changelog
file says that there are no changelogs for it instead of breaking the list.
The changelog files are bundled into the APK, which means they are read from
disk and work offline.

## Building and testing

```bash
./gradlew :app:assembleDebug        # build the app
./gradlew :app:testDebugUnitTest    # run the unit tests
```

Native code is compiled by CMake through the NDK, so a full build takes a
while. The Kotlin sources can be checked quickly with the unit test task.

## Working on the code

- Keep the existing structure: one class per file, a `Manager` for the things
  that talk to the system, a `ViewModel` that holds the state, a `Composable`
  per dialog or sheet.
- The comments explain *why* something is done the way it is done, not what
  the next line does. Keep that style.
- Every class that talks to Android needs a unit test for the part that can be
  tested without a device. Pure logic (parsers, formatters, decisions) belongs
  in a class that does not need an Android context.
