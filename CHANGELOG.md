# Changelog

All notable changes are documented here in the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
format. Releases use [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- Public v1 onboarding and the clean-history publication transition are in preparation.

### Removed

- Legacy phase and setup actions, reusable delivery workflows, and their caller examples and validators.
- Discovery of retired phase-action and reusable-workflow callers.
- Combined app-build secrets, the tool-download token, and duplicate runner-label inputs.

### Fixed

- Disabled TestFlight verification exposes the verified commit through its `sha` output.
- Invalid named build values report the `build_environment` setting that needs correction.

## [0.9.0-rc.1] - 2026-10-23

### Added

- Separate TestFlight, maintenance, App Store release and rollout composite actions.
- A cached engine with an isolated Java runtime and automatic configured-Xcode selection.
- Caller queue and permission checks with paste-ready corrections.
- Internal credential-routing, retention and phase-order checks for every composite.
- A standalone CLI zip and SHA-256 checksum; running the CLI requires JDK 21 but not Gradle.

### Changed

- Disabled TestFlight pushes and schedules still verify the app without publishing.
- App build values remain ordinary named environment values declared in `build_environment`.
- Holds keep blocking until explicitly resolved, and relevant hold details appear in preflight.

### Security

- Signing commands keep passwords out of process arguments and serialize shared runner signing state.
- Credential source validation rejects indirect or misrouted secret access.

[Unreleased]: https://github.com/thinkfishhook/mobile-delivery/compare/v0.9.0-rc.1...trunk
[0.9.0-rc.1]: https://github.com/thinkfishhook/mobile-delivery/releases/tag/v0.9.0-rc.1
