# Configure these neutral callers

Replace the neutral `.github/delivery.json` identities, project paths, test commands,
fresh report paths and runner/Xcode choices with your app's existing requirements.
Retain existing deterministic checks, app build values, binary checks, switches and schedules.
Use one integration branch consistently. All action references pin stable v1.1.0 at `d0b62a1a9b8e3f531c0ffbb97629a452cffc0268`. Merge the Dependabot grouping rule into existing rules.

Follow `docs/upgrade-v1.1.md` in the tool checkout/distribution for credentials, the packaged
local hook, structural `callerChecks`, normal dependency-PR observation, live automatic
same-package TestFlight evidence and identity-preserving rollback. Configuration success
and fixtures do not establish a real app deployment. Manual live evidence may remain
explicitly pending until naturally exercised.

The unreleased v1.1.1 reporter permission correction requires replacing all shared pins
with that patch release SHA before copying these candidate workflows. The previous
v1.1.0 validator rejects the added Checks-read grant. Stable publication refreshes the pins.
