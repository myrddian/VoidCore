# Contributing to VOIDcore

Thanks for helping shape VOIDcore.

## Before you start

- Read [README.md](README.md) for the local stack.
- Read [docs/DECISIONS.md](docs/DECISIONS.md) for the architectural rationale.
- Read the relevant [`docs/SPEC*.md`](docs/) file before changing behavior or protocol.
- [AGENTS.md](AGENTS.md) carries the rules that override default workflows.

## Development flow

1. Fork or branch from `main` — **every change gets its own branch off
   `main`.**
2. Make one focused change at a time.
3. Keep docs and migrations in sync with code changes.
4. Run verification before opening a PR.

### Don't stack branches

Once a PR is open, put follow-up work on a **new branch off `main`**, not
on the branch under review.

Pushing a commit to a branch whose PR has already merged puts that commit
nowhere: GitHub still reports the PR as `MERGED`, the branch is gone from
`main`'s history, and the work silently never lands. This has bitten this
repo three times, each time needing a cherry-pick to recover. If you must
stack, retarget the child PR to `main` *before* the parent merges.

To check that work actually landed:

```sh
git merge-base --is-ancestor <commit> origin/main && echo on-main || echo MISSING
```

### Running the stack locally

Faster than `docker compose up --build`, because Gradle rebuilds in place:

```sh
./scripts/dev-db.sh    # Postgres on :5432, same sql/init as Compose
./scripts/dev-run.sh   # the app on :8090
```

Note that Spring serves static assets from `build/resources`, so a
frontend change needs a server restart — rebuilding the bundle alone
won't show up.

## Verification

For most changes:

```sh
cd app
./gradlew check
```

`check`, not `test`: `test` is the Java suite alone, while `check` also
runs the client's vitest suite and `tsc --noEmit`. The bundler strips
types without checking them, so nothing else catches a type error.

### Local runs are weaker than CI unless you give Testcontainers a socket

The integration tests are annotated `disabledWithoutDocker`, and
Testcontainers cannot reach Docker Desktop's socket from the test JVM by
default. They then **skip silently** — over a hundred of them — so a green
local run can hide a suite that CI fails.

```sh
cd app
DOCKER_HOST="unix://$HOME/.docker/run/docker.raw.sock" ./gradlew check
```

Compare the skip count with and without it: if it drops, those tests were
never running for you. CI runs them on every push.

Be wary of assertions that compare a database round-trip against an
in-process value. Postgres `timestamptz` keeps microseconds while
`Instant.now()` carries nanoseconds on Linux and often only microseconds
on macOS, so such a test can pass locally and fail in CI for reasons that
have nothing to do with the change.

### Don't pin tests to absolute dates

A test that hardcodes an instant is a bomb with a date on it. This repo
had three that pinned a `Clock` to `2026-04-29` while the database used
its own `now()` for expiry — they passed until 2026-05-29 and then failed
every run for months with no commit behind them.

Express time as an offset from a captured `NOW` (truncated to whole
seconds), so the relationship between the pinned clock and the database
clock holds on every run. See `SessionServiceIntegrationTest`.

For stack-level changes:

```sh
docker compose config
docker compose build app cityline-door
```

## Pull requests

Good pull requests usually include:

- a short explanation of the user-visible change
- verification notes
- migration notes if schema, protocol, or config changed

## Scope

VOIDcore is the public engine repo. Instance-specific branding, private
operations tooling, and deployment overlays should stay out of core unless
they clearly improve the reusable platform surface.
