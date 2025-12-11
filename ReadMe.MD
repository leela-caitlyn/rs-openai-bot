# RuneScape AI Bot - Changelog

This changelog tracks notable updates to the RuneScape AI bot codebase, including the Java RuneLite plugin and supporting Python agents.

## [Unreleased]
### Added
- Added this changelog file to provide a running history of updates and testing notes.

### Fixed
- Renamed the RuneLite panel class file to match its `AIBrainPanel` class name, resolving a case-sensitivity build error on Linux filesystems.
- Restructured the RuneLite plugin into a standard Gradle-friendly layout under `runelite-client-plugin/src/main/...` so IDEs can resolve symbols like `AIBrainPanel` without manual configuration.
- Added a minimal Gradle build (run `gradle :runelite-client-plugin:compileJava` with a locally installed Gradle, or generate a wrapper via `gradle wrapper`) so the RuneLite plugin sources resolve dependencies like `AIBrainPanel` and `WidgetInfo` out of the box.
- Removed the binary Gradle wrapper JAR from version control; rely on a local Gradle install or regenerate the wrapper files when needed.

## Testing
- `python -m compileall rs-ai-bot`
