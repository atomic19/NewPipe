# AGENTS.md

Guidance for AI coding agents working in this NewPipe repo.

## Project context
- This repo is in maintenance mode; new features target the `refactor` branch.
- Codebase is Android app with mixed Java/Kotlin; Java toolchain is 17.
- NewPipe relies on the NewPipeExtractor library; changes there must be tested in this app.

## Contribution constraints (from CONTRIBUTING)
- AI usage is allowed only for small fixes/docs when you understand and review the code.
- Do not use AI to fill PR/issue templates.
- Follow F-Droid rules: no non-free software or closed-source Google libs.

## Build and checks
- Default checks on build include Checkstyle, ktlint, and dependency order.
- Useful commands:
  - `./gradlew assembleDebug`
  - `./gradlew lintDebug testDebugUnitTest`
  - `./gradlew connectedCheck` (instrumented tests)
- You can skip ktlint auto-format on debug builds with `-DskipFormatKtlint`.
- `packageSuffix` (in `gradle.properties` or `-DpackageSuffix=...`) changes release app id/name.

## Editing guardrails
- Prefer minimal, targeted changes; keep existing architecture and patterns.
- Respect existing resource naming and translation files (many locales under `app/src/main/res`).
- If changing database/entities, update Room schemas under `app/schemas` as needed.

## Communication expectations
- Summarize changes with file paths and note tests run (or why not).
- Call out any risks or behavior changes explicitly.
