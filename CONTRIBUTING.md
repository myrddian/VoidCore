# Contributing to VOIDcore

Thanks for helping shape VOIDcore.

## Before you start

- Read [README.md](README.md) for the local stack.
- Read [DECISIONS.md](DECISIONS.md) for the architectural rationale.
- Read the relevant `SPEC*.md` file before changing behavior or protocol.

## Development flow

1. Fork or branch from `main`.
2. Make one focused change at a time.
3. Keep docs and migrations in sync with code changes.
4. Run verification before opening a PR.

## Verification

For most changes:

```sh
cd app
./gradlew test
```

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
