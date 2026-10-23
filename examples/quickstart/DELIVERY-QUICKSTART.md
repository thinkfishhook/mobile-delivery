# Configure the copied delivery files

Copy this directory's contents into your app root, preserving `.github`:

```sh
cp -R /path/to/mobile-delivery/examples/quickstart/. /path/to/app/
```

Review existing files first: copying can replace workflows, a delivery configuration or
Dependabot rules with the same names. Merge any existing rules you need to retain.
Repository secrets and switches are set separately by the main README's Quick start.
The actions pin the tested source revision for the v1 preview. Keep deployment disabled
until your verification run succeeds.

Every app-specific edit is marked `# CHANGE ME`. In YAML these are comments. JSON does
not allow comments, so its markers are inside placeholder string values: replace the
**whole string value**, including the marker, with your value. Even when keeping an
example value, remove its marker. Never put secret values into these files.

1. Use `.github/delivery.json` for a native Xcode app. For KMP, first copy
   `.github/delivery.kmp.example.json` over it. Delete the unused example when finished.
2. Replace the marked identity values. Get the app ID and internal group ID from App
   Store Connect and the team ID from your signing account. Set `baseline` to the full
   commit SHA of the first app revision that may be delivered (`git rev-parse HEAD`).
   Select an installed Xcode version and simulator. The native template uses a project;
   if your app requires a workspace, add `ios.workspace` and change both test commands
   from `-project` to `-workspace` with that workspace path.
3. Match the native scheme and its shared `UnitTests`/`UITests` plans, or the KMP Gradle
   tasks and report directories. Every listed report must contain passing, unskipped
   tests. Select public fallback notes and tester instructions for your app.
4. Set the same branch in all four workflows and the TestFlight push filter. Choose
   hosted or self-hosted Mac labels for TestFlight and maintenance, then review the
   maintenance and App Store schedules. The App Store switch starts false.
5. If builds need app secrets, add `build_environment` to the JSON with their names and
   bind those existing secrets in `env:` on the TestFlight action step. No new combined
   secret or secret-presence JSON is required. Leave this out when unnecessary.

The four actions already use one complete commit SHA with its version comment.
Keep them together when upgrading; Dependabot groups their updates. The hold form is
ready to use. Maintenance creates its `testflight-hold` label when enabled; to file a hold
earlier, create that label with `gh label create testflight-hold --force`.

`stale.yml.example` is optional and inactive. If you use a stale bot, either copy its
hold/incident exemptions into your existing policy or review its marked values and rename
it to `stale.yml`. Never auto-close `testflight-hold` issues to lift a release hold.

Finish with the main README: configure `ios-delivery`, add its secrets, set both switches
to `false`, and push to get the first green verification run before enabling delivery.
