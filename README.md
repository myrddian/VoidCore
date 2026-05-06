# VOIDcore

VOIDcore is a self-hosted typed-document community platform with terminal
aesthetics and BBS-flavoured social primitives.

It is designed for communities that want durable, queryable knowledge instead
of losing everything inside chat silos. The core model is:

- typed Markdown documents with schema-driven frontmatter
- terminal-native navigation and presentation
- social primitives that stay in their own lane: chat, message boards,
  mail, mentions, and doors

This repository is the fresh public-engine split described in the migration
plan. It already has the copied engine, renamed package tree, and cleaned repo
structure; the remaining work is public-facing polish and verification.

## Repo Layout

```text
VoidCore/
├── app/                 # Spring Boot application
├── config/              # core Postgres config
├── doors/               # door runtimes and SDKs
├── scripts/             # repo maintenance helpers
├── sql/init/            # Postgres init scripts
├── SPEC*.md             # product and protocol contracts
├── DECISIONS.md         # ADRs
├── ROADMAP.md           # product direction
└── AGENTS.md            # project working rules
```

## Quick Start

```sh
git clone <repo> /opt/voidcore
cd /opt/voidcore
cp .env.example .env
chmod 600 .env
make secrets
make env-check
make up
```

The default stack is intentionally small:

- `postgres`
- `app`

No backup sidecars, instance-private deploy automation, or overlay-specific
ops tooling are carried in this public-engine repo.

## Key Docs

- [SPEC.md](/Users/enzoreyes/proj/VoidCore/SPEC.md): technical contract
- [SPEC-documents.md](/Users/enzoreyes/proj/VoidCore/SPEC-documents.md):
  typed-document model
- [SPEC-screens.md](/Users/enzoreyes/proj/VoidCore/SPEC-screens.md):
  screen architecture
- [SPEC-doors.md](/Users/enzoreyes/proj/VoidCore/SPEC-doors.md): door protocol
- [DECISIONS.md](/Users/enzoreyes/proj/VoidCore/DECISIONS.md): architectural
  rationale
- [ROADMAP.md](/Users/enzoreyes/proj/VoidCore/ROADMAP.md): medium-term direction

## Current State

This repo has already been:

- copied into a fresh Git repository
- renamed to `io.aeyer.voidcore`
- cleaned of local build junk and instance-only baggage

The remaining work is:

- neutralising residual sample data and example text
- trimming overlay-history framing from some docs and migration notes
- verifying the build under the new namespace

## License

OSS metadata and license files still need to be added as part of the first
public-release pass.
