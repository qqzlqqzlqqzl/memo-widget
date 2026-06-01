# Contributing to Memo Widget

Thanks for taking the time to improve Memo Widget. This project is still early-stage, so small, focused changes are easiest to review.

## Good first contributions

- Bug reports with Android version, device model, app version, and reproduction steps.
- Documentation fixes in `README.md`, `USER_GUIDE.md`, or `CHANGELOG.md`.
- Regression tests for sync, local storage, iCalendar parsing, widgets, and settings flows.
- Small UI or reliability fixes that do not change the data model.

## Development setup

```bash
git clone https://github.com/qqzlqqzlqqzl/memo-widget.git
cd memo-widget
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

The app is built with Kotlin, Jetpack Compose, Glance widgets, Room, WorkManager, Ktor, and EncryptedSharedPreferences.

## Pull request checklist

- Keep the change focused on one problem.
- Add or update tests when behavior changes.
- Update docs or `CHANGELOG.md` for user-visible changes.
- Do not commit personal GitHub tokens, API keys, keystores, or local config.
- Verify `./gradlew :app:testDebugUnitTest` before opening a PR when possible.

## Security-sensitive areas

Please be especially careful around GitHub PAT handling, AI API key storage, sync conflict resolution, network security configuration, Android exported components, and release signing. If you think you found a vulnerability, use the private reporting process in `SECURITY.md` instead of opening a public issue.
