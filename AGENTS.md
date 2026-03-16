**Agents Guide**
- Purpose: short, actionable instructions for automated coding agents operating in this repo
- Location: run all commands from repository root; use `./gradlew` (wrapper) to ensure the correct Gradle/JDK/tooling

- Important: this repository is a multi-module Kotlin/Java Gradle project (Paper/Folia fork). The build uses Java toolchain 21; tests and run tasks expect JDK 21.

- Quick commands
  - Build everything: `./gradlew build` (use `--parallel` for faster builds where appropriate)
  - Run checks only: `./gradlew check`
  - Run tests for a specific subproject: `./gradlew :plugins:plugin-islands:test`
  - Skip tests while building: `./gradlew build -x test`
  - Run a development server (Folia): `./gradlew :folia-server:runDevServer`
  - Run an assembled server: `./gradlew :folia-server:runServer`
  - List available tasks: `./gradlew tasks --all`

- Running a single test (recommended way)
  - Run a single test class in a subproject (Gradle + JUnit 5):

    ```bash
    ./gradlew :plugins:plugin-islands:test --tests "net.azisaba.vanilife.islands.portal.HologramSpawnerTest"
    ```

  - Run a single test method:

    ```bash
    ./gradlew :plugins:plugin-islands:test --tests "net.azisaba.vanilife.islands.portal.HologramSpawnerTest.testSpawnHologram"
    ```

  - Notes:
    - Use the fully qualified class name. Surround the `--tests` pattern with quotes to avoid shell expansion.
    - Gradle's `--tests` supports patterns/wildcards (e.g. `*Hologram*`).
    - Some projects (notably `:folia-server`) configure `useJUnitPlatform { excludeTags("Slow") }` in their `test` task. By default slow tests will be skipped there.
    - If a test task adds JVM agents (mockito agent for Java 21) or other `jvmArgumentProviders`, the task configuration will inject them automatically — prefer the Gradle task above over running tests with raw `java`.

- Debugging tests
  - Run with stacktrace and verbose logging: add `--stacktrace --info` or `--debug` to your Gradle command.
  - IDE: use IntelliJ's Run gutter for quick single-test execution and debugging.

- Lint & formatting
  - There is no enforced ktlint/detekt/spotless configuration in repository root. The project uses Kotlin and Java; prefer the Kotlin official style and IntelliJ formatter.
  - Recommended (not yet configured): add `ktlint` and `detekt` and wire them into `./gradlew check`.
  - Import rules: avoid wildcard imports (`*`), group and order imports sensibly (standard library -> third party -> project), and keep imports alphabetized inside groups.

- Code Style Guidelines (applies to Kotlin & Java plugin code)
  - Language & tooling
    - Kotlin is the primary language for plugin code under `plugins/`.
    - Java code exists (server/upstream). Keep Java changes idiomatic to the surrounding code style.
    - Use the Gradle wrapper `./gradlew` and Java toolchain (Java 21) defined by the build scripts.

  - Formatting
    - Use 4-space indentation.
    - Opening brace on same line (Kotlin default style).
    - Keep lines reasonably short (wrap at ~120 chars).
    - Use the IDE formatter or a project ktlint configuration if added.

  - Imports
    - No wildcard imports. Be explicit.
    - Group imports with a blank line between standard library (kotlin/java), third-party (org/, com/, io/), and project-local packages (`net.azisaba.*`).
    - Keep imports alphabetized within each group.

  - Naming
    - Packages: lower case dot-separated (already `net.azisaba.vanilife.*`).
    - Classes/objects: UpperCamelCase.
    - Functions / properties: lowerCamelCase.
    - Constants (public `const val`): UPPER_SNAKE_CASE inside companion objects or top-level objects.
    - Test class names: `<Thing>Test` for unit tests and `*TestSuite` for suites.

  - Types & nullability (Kotlin)
    - Prefer `val` over `var`. Use `var` only when mutation is required.
    - Prefer explicit return types for public functions and members; internal/private functions may omit when obvious.
    - Embrace Kotlin nullability: prefer `?.`, `?:`, `let`, and `requireNotNull` where appropriate; avoid unchecked platform-type assumptions when interacting with Java APIs.

  - Error handling & logging
    - Do not use `e.printStackTrace()` in plugin code. Instead, log contextual information and the exception.
      - Example (preferred): `plugin.logger.log(Level.SEVERE, "Failed to update hologram UUID in repository", e)`
    - Catch the most specific exception possible. Avoid catching `Throwable` unless you need to guard a boundary and explicitly document why.
    - Do not swallow exceptions silently. Log and either handle or rethrow.
    - Avoid leaking secrets into logs.

  - Coroutines & concurrency
    - This project uses coroutines and Folia region dispatchers in plugin code (see `plugin.launch(plugin.regionDispatcher(loc))`).
    - When interacting with Folia/async server internals, schedule actions on the correct dispatcher (region/world thread) rather than running blocking logic on caller threads.
    - Prefer structured concurrency: tie coroutine lifecycles to plugin lifecycle where appropriate and close resources explicitly.

  - Dependency Injection
    - Use Koin for DI as in plugin Main classes. Start/stop the `KoinApplication` in `onEnable`/`onDisable` and keep registrations minimal and explicit.

  - Public API & documentation
    - Add KDoc to public API surface. Keep comments for non-obvious algorithms only.
    - Avoid TODOs without ticket/issue references.

  - Tests
    - Use JUnit 5 (junit-jupiter); tests are configured with `useJUnitPlatform()` in server modules.
    - Tag slow/integration tests with `@Tag("Slow")` and be mindful that `:folia-server:test` excludes that tag by default.
    - Keep tests hermetic when possible (mock external services). The build already wires a Mockito javaagent for certain test tasks.

- Git / commits
  - Follow existing commit message style in the repo (conventional-ish: `fix(module): short description`).
  - Do not rewrite or amend commits unless explicitly required and coordinated with repo owners.

- Files and locations that matter (quick map)
  - Root build: `build.gradle.kts`
  - Version catalog: `gradle/libs.versions.toml`
  - Folia/server project: `:folia-server` (tasks: `runServer`, `runDevServer`, `test`)
  - Plugins live under `plugins/` (e.g. `:plugins:plugin-islands`, `:plugins:plugin-npc`)

- Cursor/Copilot rules
  - Cursor rules (.cursor/rules/ or .cursorrules): none found in repo.
  - Copilot instructions (.github/copilot-instructions.md): none found in repo.

- Troubleshooting hints
  - If tests warn `SLF4J(W): Defaulting to no-operation (NOP) logger implementation` add a test logging binding (e.g. `org.slf4j:slf4j-simple`) to the test runtime or configure logger inside tests.
  - If Gradle fails with a JDK mismatch, ensure JDK 21 is used or rely on `./gradlew` which uses the configured Java toolchain.
  - If a test hangs, run the test class directly from IntelliJ with debugger to inspect threads, or rerun Gradle test with `--debug` and `--stacktrace`.

- If you change formatting or static analysis rules
  1. Add the tool (ktlint/detekt/spotless) to the Gradle build and wire into `check`.
  2. Update this `AGENTS.md` with the exact commands for CI and local runs.

Appendix: Example single-test invocations (copy/paste)

  - Class
    `./gradlew :plugins:plugin-islands:test --tests "net.azisaba.vanilife.islands.portal.HologramSpawnerTest"`
  - Method
    `./gradlew :plugins:plugin-islands:test --tests "net.azisaba.vanilife.islands.portal.HologramSpawnerTest.testSpawnHologram"`

If anything in this document is unclear or you need a change to tooling (add ktlint/detekt, configure CI linting, or add a recommended gradle task), update this file and add a short PR explaining why.
