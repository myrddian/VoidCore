# Extending VOIDcore

> **Audience:** operators running their own VOIDcore instance who want
> to add scene-specific document types, seed data, or branding without
> forking the core engine.

VOIDcore keeps the engine small on purpose. Scene-specific types, seed data,
and specialty screens should live outside core whenever they are not broadly
useful to every deployment.

This is the first generic extension hook now present in the split repo:

- core Flyway migrations from `classpath:db/migration`
- optional overlay migrations from an operator-mounted filesystem path

## Overlay migrations

By default, VOIDcore only loads the built-in migration chain:

```text
classpath:db/migration
```

An operator can opt into overlay-supplied repeatable migrations by setting:

```text
VOIDCORE_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:/instance/migrations
```

The core repo does not ship an `/instance` directory or any scene-specific
content. That path is intentionally external so deployments can pin their own
overlay repo or bind-mount custom migrations without forking core.

## Example compose override

One straightforward deployment pattern is a compose override that mounts a
private overlay directory into the app container:

```yaml
services:
  app:
    volumes:
      - ./instance/migrations:/instance/migrations:ro
    environment:
      VOIDCORE_FLYWAY_LOCATIONS: >-
        classpath:db/migration,filesystem:/instance/migrations
```

## Recommended responsibilities

Good overlay candidates:

- scene-specific document types such as `release`
- seed data that belongs to one community only
- highly themed screens that are really product features for one instance
- branding assets and local copy

Keep in core:

- generic typed-document substrate behavior
- chat, mail, message board, polls, and door protocols
- ACL and feature-toggle infrastructure
- reusable screens that make sense for most deployments

## Current limitation

This hook covers repeatable SQL migrations only. It does not yet provide a
formal plugin loader for Java screens or beans. That means overlay-supplied
code is still a later step; the current split only establishes the migration
boundary honestly.
