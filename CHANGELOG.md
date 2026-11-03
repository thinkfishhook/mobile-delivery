# Changelog

All notable changes are documented here in the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
format. Releases use [Semantic Versioning](https://semver.org/).

## [1.0.1] - 2026-11-03

### Added

- A portfolio acceptance evidence guide covering scheduled runs, receipt triggers,
  grouped upgrades, runner queue measurements and seven-day hosted macOS billing.

## [1.0.0] - 2026-11-03

### Added

- Failure annotations from every available JUnit and Xcode result bundle, including after a
  non-zero test command. Step summaries and `run-summary.json` retain every failing test.
- Quiet successful verification logs with command durations and per-report test totals;
  failed commands expose a collapsed output tail while full logs remain in evidence.
- One actionable verification issue per red streak, updated with new, persistent and
  recovered failures, suspect commits, reproduction commands and evidence links.
  Receipt-confirmed delivery closes it as completed with the fixing commit and build;
  superseded untested commits do not change it.
- A bounded, redacted, versioned JSON block in red-streak issues for agent consumers.
- `needs-owner` labels and explicit remediation for credential, receipt and Apple-state
  incidents; ordinary test failures remain agent-actionable.
- A fail-closed, read-only workflow drain helper for repository cutovers.
- Separate TestFlight, maintenance, App Store release and rollout composite actions.
- A cached engine with an isolated Java runtime and automatic configured-Xcode selection.
- Caller queue and permission checks with paste-ready corrections.
- Internal credential-routing, retention and phase-order checks for every composite.
- A standalone CLI zip and SHA-256 checksum; running the CLI requires JDK 21 but not Gradle.
- A copy-in native Xcode/KMP quickstart and grouped GitHub Actions updates.

### Changed

- v1 targets Fish Hook's portfolio through the public `thinkfishhook/mobile-delivery`
  repository, with full-SHA composite pins and grouped Dependabot upgrades.
- Quickstart guidance excludes receipt tags from push workflows and avoids duplicate CI
  for Dependabot branch pushes and pull requests.
- Superseded pushes cancel themselves without verification or delivery work. TestFlight
  callers must grant `actions: write`; policy failures print the required correction.
- Candidate release packages may read the upcoming stable changelog section before its
  date is known. Stable release tags still require a dated, non-empty version section.
- Disabled TestFlight pushes and schedules still verify the app without publishing.
- App build values remain ordinary named environment values declared in `build_environment`.
- Holds keep blocking until explicitly resolved, and relevant hold details appear in preflight.
- Workflow discovery reads the composite in each run's exact caller revision.

### Removed

- Legacy phase and setup actions, reusable delivery workflows, and their caller examples and validators.
- Combined app-build secrets, the tool-download token, and duplicate runner-label inputs.

### Fixed

- Maintenance refreshes delivery-branch history before selecting commits.
- Engine checks read complete Xcode version output.
- Verification parses failed-command reports and preserves structured failure evidence
  instead of hiding failures in the evidence archive.
- Duplicate ordinary delivery incidents are retired when a red-streak issue owns verification.
- Expired, not-yet-valid or mismatched distribution certificates and provisioning profiles
  fail before signing with secret names, validity dates when available and replacement commands.
- Rejected App Store Connect keys and receipt tokens identify the affected secrets and
  `gh secret set ... --env ios-delivery` remediation without exposing credential values.
- Disabled TestFlight verification exposes the verified commit through its `sha` output.
- Invalid named build values report the `build_environment` setting that needs correction.

### Security

- Signing commands keep passwords out of process arguments and serialize shared runner signing state.
- Credential source validation rejects indirect or misrouted secret access.
