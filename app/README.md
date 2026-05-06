# app/

This directory contains the Spring Boot application for VOIDcore.

High-level layout:

```text
app/
├── Dockerfile
├── Dockerfile.runtime
├── build.gradle.kts
├── gradlew
├── settings.gradle.kts
└── src/
    ├── main/
    │   ├── frontend/
    │   ├── java/io/aeyer/voidcore/
    │   └── resources/
    └── test/
        └── java/io/aeyer/voidcore/
```

Key characteristics:

- Gradle Kotlin DSL build
- Spring Boot application
- Flyway-managed database migrations
- generated jOOQ sources committed under `src/jooq/java/`
- frontend bundle compiled into `src/main/resources/static/`

Typical local commands:

```sh
./gradlew test
./gradlew bootRun
./gradlew bootJar
```

The repo is still mid-sanitisation, so some docs and sample data may still
reflect pre-split history even though the package namespace and runtime
surface now live under `io.aeyer.voidcore`.
