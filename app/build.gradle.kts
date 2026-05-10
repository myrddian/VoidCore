plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("nu.studer.jooq") version "9.0"
}

group = "io.aeyer.voidcore"
version = "0.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

extra["jooqVersion"] = "3.19.11"

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Persistence: jOOQ + Flyway + Postgres
    // Spring Boot manages versions for jooq starter and flyway-core; the
    // postgres-specific Flyway module is required from Flyway 10+.
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Auth
    implementation("de.mkammerer:argon2-jvm:2.11")

    // Rate limiting / in-process caches
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Door-side LLM gateway
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Logging — JSON layout to stdout per SPEC §14
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Typed document substrate validation (A3/A4)
    implementation("com.networknt:json-schema-validator:1.5.6")

    // In-process extension runtime
    implementation("org.graalvm.polyglot:polyglot:25.0.2")
    implementation("org.graalvm.polyglot:js:25.0.2")

    // jOOQ code generator (used by the generateJooq task — see ADR-005a)
    jooqGenerator("org.postgresql:postgresql:42.7.4")
    jooqGenerator("org.jooq:jooq-meta-extensions:3.19.11")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework:spring-websocket")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    // Testcontainers 1.21.3 still pulls docker-java 3.3.6 transitively; force
    // 3.5.x which uses Docker API 1.43+ (Docker Desktop 4.18+ rejects 1.32).
    testImplementation("com.github.docker-java:docker-java-api:3.5.0")
    testImplementation("com.github.docker-java:docker-java-transport-zerodep:3.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ----- jOOQ codegen (ADR-005a) ----------------------------------------------
// Generates DSLContext-typed table/column references from the Flyway-applied
// schema. The 'generateSchemaSourceOnCompilation = false' switch means the
// codegen task does NOT run on every build — generated classes live under
// src/jooq/java/ and are committed to git. Run `./scripts/regenerate-jooq.sh`
// after editing a migration. SPEC §14 / ADR-005a target is Testcontainers-
// driven codegen on every build; that requires Docker to be reliably reachable
// from inside a Java process and is blocked on a Docker Desktop hardening
// issue. When that's resolved this section moves to a Testcontainers task.
jooq {
    version.set("3.19.11")
    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(false)
            jooqConfiguration.apply {
                logging = org.jooq.meta.jaxb.Logging.WARN
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = (project.findProperty("voidcore.jooq.url") as String?
                            ?: "jdbc:postgresql://localhost:55440/voidcore")
                    user = (project.findProperty("voidcore.jooq.user") as String?
                            ?: "postgres")
                    password = (project.findProperty("voidcore.jooq.password") as String?
                            ?: "devpw")
                }
                generator.apply {
                    name = "org.jooq.codegen.JavaGenerator"
                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        // Exclude Flyway's own table + the catalog noise from
                        // pgcrypto / built-in regex helpers that come along
                        // with the public schema scan.
                        excludes = listOf(
                                "flyway_schema_history",
                                "regexp_matches.*",
                                "regexp_split_to_.*",
                                "pgp_armor_headers.*"
                        ).joinToString("|")
                        // Map CITEXT and INET to String. Without these, both
                        // come through as Object because they're not in jOOQ's
                        // built-in type map; explicit forced-types here mean
                        // repos see typed Field<String> for handles and IPs.
                        forcedTypes.addAll(listOf(
                                org.jooq.meta.jaxb.ForcedType().apply {
                                    name = "VARCHAR"
                                    includeTypes = "(?i:citext|inet)"
                                }
                        ))
                    }
                    generate.apply {
                        isRecords = true
                        isImmutablePojos = true
                        isFluentSetters = false
                        isJavaTimeTypes = true
                    }
                    target.apply {
                        packageName = "io.aeyer.voidcore.jooq"
                        directory = "src/jooq/java"
                    }
                }
            }
        }
    }
}

sourceSets {
    main {
        java {
            srcDir("src/jooq/java")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // docker-java pins API version to 1.32 by default; Docker Desktop 4.18+
    // rejects anything below 1.40. Force a current API version through to
    // the test JVM. Picked up by DefaultDockerClientConfig in docker-java.
    systemProperty("api.version", System.getProperty("api.version", "1.43"))
    // Honour a user-set DOCKER_HOST (e.g. unix:///.../docker.raw.sock to
    // bypass Docker Desktop's intercepting CLI socket) — see the comment
    // block above the testcontainers deps for the why.
    System.getenv("DOCKER_HOST")?.let { environment("DOCKER_HOST", it) }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("voidcore.jar")
}

// ----- Frontend bundle (esbuild) ---------------------------------------------
// Bundles app/src/main/frontend/src/main.ts and the index/CSS into
// app/src/main/resources/static/ so they're packaged into the fat jar.

val frontendDir = layout.projectDirectory.dir("src/main/frontend")
val staticDir = layout.projectDirectory.dir("src/main/resources/static")

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "frontend"
    description = "Install esbuild + typescript locally for the frontend bundle."
    workingDir = frontendDir.asFile
    inputs.file(frontendDir.file("package.json"))
    outputs.dir(frontendDir.dir("node_modules"))
    commandLine("npm", "install", "--no-audit", "--no-fund")
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    group = "frontend"
    description = "Run esbuild to bundle the smart-terminal client into static/."
    dependsOn(npmInstall)
    workingDir = frontendDir.asFile
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("index.html"))
    outputs.file(staticDir.file("main.js"))
    outputs.file(staticDir.file("index.html"))
    outputs.file(staticDir.file("theme.css"))
    outputs.dir(staticDir.dir("fonts"))
    commandLine("npm", "run", "build")
    doFirst { staticDir.asFile.mkdirs() }
    doLast {
        // Copy the static assets that esbuild doesn't bundle (HTML + CSS).
        copy {
            from(frontendDir.file("index.html"))
            into(staticDir)
        }
        copy {
            from(frontendDir.dir("src/theme.css"))
            into(staticDir)
        }
    }
}

tasks.named("processResources") { dependsOn(frontendBuild) }
tasks.named("compileTestJava") { mustRunAfter(frontendBuild) }
