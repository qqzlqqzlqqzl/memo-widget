# Maintenance Status

Last reviewed: 2026-06-01

Memo Widget is actively maintained as a small personal open source Android project. The project is early-stage and does not yet claim broad adoption, but it has a real maintenance surface: Android releases, widget behavior, GitHub sync reliability, encrypted token storage, calendar file compatibility, dependency updates, and user documentation.

## Current Focus

- Keep the public repository easy to evaluate for users and reviewers.
- Maintain clear security guidance for GitHub PAT and AI API-key handling.
- Keep CI, Dependabot, issue templates, PR templates, and release notes in place.
- Stabilize release packaging and signing for future APK releases.
- Improve multi-device sync conflict handling.
- Expand calendar compatibility around iCalendar reminders and recurrence rules.

## Maintainer Responsibilities

- Review dependency updates and Android security advisories.
- Triage reported bugs and security-sensitive issues.
- Keep release notes accurate when public APKs are published.
- Verify token and API-key handling before merging storage, network, or settings changes.
- Keep user-facing setup instructions current.

## Next Release Candidates

- Proper release signing and Play Store-ready packaging.
- Better conflict resolution UI for simultaneous edits.
- iCalendar `VALARM`, `UNTIL`, `COUNT`, and `EXDATE` support.
- Widget screenshot refresh with real-device captures.
- Regression tests for sync conflict and reminder scheduling behavior.

## Non-Goals

- The project does not collect telemetry or user note content.
- The maintainer does not provide hosted sync infrastructure; users own their GitHub-backed data.
- The optional AI Q&A feature is provider-configurable and should not require a specific vendor.
