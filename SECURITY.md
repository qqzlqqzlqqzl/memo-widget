# Security Policy

Memo Widget stores user-owned notes and calendar events in a GitHub repository chosen by the user. It also handles sensitive local settings such as GitHub Personal Access Tokens and optional OpenAI-compatible API keys.

## Supported versions

Security fixes target the current `master` branch and the latest published release when a release artifact is available.

## Reporting a vulnerability

Please do not open a public GitHub issue for vulnerabilities or suspected token leaks.

Use GitHub's private vulnerability reporting if it is available on this repository. If private reporting is not available, open a minimal issue that says you need a private security contact, without including exploit details, tokens, stack traces containing secrets, or proof-of-concept payloads.

Helpful details include:

- Affected version or commit.
- Android version and device model.
- Whether the issue involves GitHub PATs, AI API keys, local storage, network traffic, exported Android components, widget behavior, or release signing.
- Reproduction steps with redacted secrets.
- Expected and actual impact.

## Security design notes

- GitHub PATs and AI API keys are stored through Android Keystore-backed encrypted preferences.
- Screens that can expose secrets use `FLAG_SECURE` where appropriate.
- Plain HTTP is blocked by default except loopback hosts used for local providers such as Ollama.
- Dependency updates are tracked with Dependabot.
- CI runs compilation, unit tests, lint, and release smoke checks.

Security reports are prioritized above ordinary feature work.
