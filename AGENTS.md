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
- `changelogs/` - the release notes, one markdown file per version that has
  them (a released version can be missing its file)

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
`versionCode` of the build. Releases are cut from `main`: every push starts the
workflow in `.github/workflows/build-and-release.yml`, which picks the next
number from the tags that already exist and publishes the release at the end of
the run. The version under development is therefore the highest number that no
other build is about to take.

**Ask the remote, not the working copy.** A local clone can be behind, and the
files in `changelogs/` are not a record of what was released: a file can exist
for a release that never finished, and a released version can have no file at
all. Always read the latest release from GitHub before you pick a number, and
never take it from the highest file name in `changelogs/`.

```bash
# The latest release that actually exists on GitHub (prints e.g. 31).
git ls-remote --tags --refs origin \
  | sed -E 's#.*refs/tags/V([0-9]+)$#\1#' \
  | grep -E '^[0-9]+$' | sort -n | tail -n 1
```

If the GitHub CLI is available, `gh release list --limit 1` prints the same
release together with its notes. On Windows, plain
`git ls-remote --tags --refs origin` and reading the highest `V` number gives
the same answer. Local tags, `git describe --tags` and `changelogs/*.md` are
only hints; the remote is the answer.

**Ask the workflow too, not only the tags.** The tag is created at the very end
of the run, in the `Publish GitHub release` step, so a build that is still
running has not created its tag yet and the tag list makes that number look
free. The same holds for a build queued behind the one that is running. If you
write your entry under a number one of those builds will take, your notes end
up on a release that does not contain your change, and the release that does
contain it says that there are no changelogs. That is the trap when several
agents work at once, or when you work while a version is being built.

Every build that is queued or in progress owns one number, and it owns it
before yours: the workflow is serialized (see `concurrency` in the file), and
your own push starts a build of its own.

```bash
# The runs that are still going to publish a tag.
gh run list --workflow build-and-release.yml --limit 20 \
  --json status,conclusion,event,createdAt,url
```

Read the list like this:

- `status: in_progress` and `status: queued` are builds that will publish the
  next tags. `status: completed` does not count: a successful run has its tag
  in the tag list already, and a failed or cancelled run never created one.
- The runs finish in the order they were created and your build comes after
  every one already waiting, so add one number for each run still ahead of you.
- `workflow_dispatch` runs publish a tag just like a `push` run does, so they
  count the same way.
- At most one run is in progress and one is queued at a time, so no unfinished
  runs means nothing is in flight.

With `V31` as the latest release and one build in progress, that build becomes
`V32` and the entry goes into `changelogs/V33.md`. If `gh` is not installed,
`curl -s "https://api.github.com/repos/<owner>/<repo>/actions/runs?per_page=20"`
returns the same runs (`<owner>/<repo>` from `git remote get-url origin`), and
the Actions tab of the repository shows them as well.

The workflow is short. Read the `Determine next version` and
`Publish GitHub release` steps when you are unsure what a build will do.

Rules:

1. Check the published tags **and** the unfinished runs of the release
   workflow when you start and again before you finish: another agent may have
   pushed, or a build may have taken the number, in between. Re-check instead
   of reusing a number you captured earlier in the session.
2. Add your entry to `changelogs/V<next version>.md`. Create that file with
   the heading `# V<next version>` when it does not exist yet.
3. Never move your entry into the file of a version that will ship without
   your change. Once your change is pushed its number is fixed: the tag is
   created for that build's number and a file under another number ships as
   "no changelogs". Before the push, renaming your own file to the right
   number is just fixing your work. Never write into the file of a version
   whose build has already started: that build read its changelog from its own
   commit, and the notes of a released version are already shown to everyone
   who updated to it.
4. If your work is big enough to deserve its own release, say so in your
   summary. The number is still picked by the release workflow from the
   published tags, so the file name always follows the releases.
5. Say which number you used and which builds you counted in your summary. If
   a build you counted was cancelled, the next agent needs to see the
   reasoning to correct the number before the push.

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
