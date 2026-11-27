# Upgrade to split delivery

Use the [setup guide](https://github.com/thinkfishhook/mobile-delivery#quick-start) and a single compatible full release SHA across all
shared actions. The v1.1.1 reporter added Checks read, which the v1.1.0 caller validator
rejects. Use a CLI at least as recent as v1.1.1 with those callers.

Replace the old automatic TestFlight push trigger in the same PR that adds CI and
standalone deployment. Retain the app's checks, runner choices, switches and schedules.
Use the shared workflow examples for PR checks and failure reporting. Update the
[local review hook](local-risk-review.md) separately if installed.

Record the caller and tool SHAs in the app repository. Observe a normal integration
push and link CI and automatic deployment. Match source SHA, producer run and attempt,
build identity and package digest, then confirm internal TestFlight availability and
its receipt. Leave live manual delivery evidence pending until an app actually needs
a manual delivery.

## Rollback

Before the first schema-2 reservation, restore the prior callers and pins together.
Once schema-2 reservations exist, keep compatible readers. Stable v1.0.2 and earlier
cannot safely read that state. Preserve preparation refs, receipts and retained package
bytes. Pause new preparation with the delivery switch if needed and let accepted uploads
finish recovery before changing the caller shape. Use the original deployment run for
receipt or distribution recovery. Keep App Store enablement a separate decision.
