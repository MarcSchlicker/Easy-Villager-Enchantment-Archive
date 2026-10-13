# Releasing

## Before a release

- Build with Java 21: `./gradlew build compileIntegrationJava`.
- Run the isolated integration server after accepting the Minecraft EULA, as described in the README.
- Test a clean Minecraft 1.21.1 / NeoForge installation with Easy Villagers, both with and without JEI.
- Test a real dedicated server with the add-on installed on both client and server.
- Check archive insertion/removal, paging, buying, restocking, worker return, block breaking, manual rolls, auto-roll, text input and expanded-panel layout.
- Use real screenshots from the current version. Do not use mockups in place of in-game images or show companion-mod features as part of this project.
- Verify that the release contains no dependency JARs, worlds, logs, credentials or absolute local paths.
- Keep the mod ID `easy_villagers_enchantment_library` unchanged. It identifies existing blocks and world data.
- Update `CHANGELOG.md` and the `mod_version` in `gradle.properties`.

## GitHub release

Create a tag such as `v0.2.3`, then attach only the JAR from `build/libs/`. Do not upload the complete `build` directory or any dependency JARs. Until both clean-client and dedicated-server checks are complete, use a prerelease/beta label.

## Distribution pages

Declare Easy Villagers as required and JEI as optional. Mark Minecraft 1.21.1 and NeoForge accurately. Use the MIT license for this add-on's code, link the source repository and issue tracker, and answer each platform's submission questions accurately.
