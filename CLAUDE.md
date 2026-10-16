# MinimaLauncher

Android launcher (Kotlin). Development happens on the branch given in the session
instructions; commit and push there.

## Release (Obtainium)

The user installs the app via Obtainium from the GitHub releases of this repository.
When asked for a new version (e.g. "fai la 0.75", "pubblica su Obtainium"), after
pushing the commits:

1. Find the latest tag: `git ls-remote --tags origin` (tags are `vX.Y`, versionCode = X*1000+Y).
2. Pushing a tag from the cloud session is refused by the git proxy, so trigger the
   workflow instead: GitHub Actions `release.yml` with `workflow_dispatch`, input
   `version` = the new `X.Y`, ref = the current branch. The workflow creates the tag,
   builds the signed APK and publishes the GitHub release `MinimaLauncher vX.Y`.
3. Wait until the release exists (check the release by tag), then give the user the
   release link. The release build also doubles as the compile check: the Gradle build
   cannot run in the cloud session because Maven/Google repositories are blocked.

Do not create a release unless the user asks for a version.
