# Building Chest Tracker Melhor

This is the complete source of Chest Tracker Melhor, a modified version of
[ponuing/ChestTracker](https://github.com/ponuing/ChestTracker) (branch `26.3`, commit `85be89c`).
The full list of changes is in `modrinth/changelog.md`.

The GitHub repository has everything needed to build. To stay small, the `-sources.zip` attached
to each release leaves out files that are not part of these changes and can be obtained elsewhere:

- `images/`: promotional images, identical to the upstream repository.
- `libs/litematica-*.jar` and `libs/malilib-*.jar`: identical to the upstream repository at the
  commit above. Put them back into `libs/`.
- `local-maven/` and `libs/jackfredlib/`: Where Is It 2.7.5.2+26.3 and JackFredLib 0.10.6+26.3,
  both LGPL-3.0. Upstream downloads them from GitHub Packages, which requires a login. Instead,
  take them from any released Chest Tracker jar:
  - `META-INF/jars/whereisit-2.7.5.2+26.3.jar` goes to
    `local-maven/red/jackf/whereisit/2.7.5.2+26.3/`, with a pom that depends on
    `red.jackf:jackfredlib:0.10.6+26.3`.
  - Inside it, `META-INF/jars/jackfredlib-0.10.6+26.3.jar` goes to
    `local-maven/red/jackf/jackfredlib/0.10.6+26.3/`, with a plain pom.
  - The jars inside that one (`jackfredlib-base`, `-gps`, `-toasts`, and so on) go to `libs/jackfredlib/`.

Then, with Java 25:

```
./gradlew build
```

The jar for players and servers is `build/libs/ChestTrackerMelhor.jar`.
Automated tests (singleplayer and dedicated server): `./gradlew runClientGameTest`.
