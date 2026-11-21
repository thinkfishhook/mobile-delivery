# Configure these neutral callers

Replace the neutral `.github/delivery.json` identities, project paths, test commands,
fresh report paths and runner/Xcode choices with your app's existing requirements.
Retain existing deterministic checks, app build values, binary checks, switches and schedules.
Use one integration branch consistently. All shared action references pin stable v1.1.1 at `1050b2bf67c2e22289ee7452cf12bf089b6699ba`. Merge the Dependabot grouping rule into existing rules.

Follow `docs/upgrade-v1.1.md` in the tool checkout/distribution for credentials, the packaged
local hook, structural `callerChecks`, normal dependency-PR observation, live automatic
same-package TestFlight evidence and identity-preserving rollback. Configuration success
and fixtures do not establish a real app deployment. Manual live evidence may remain
explicitly pending until naturally exercised.

The v1.1.1 reporter requires Checks read. Use the matching v1.1.1 CLI; the previous
v1.1.0 validator rejects this corrected permission set.
