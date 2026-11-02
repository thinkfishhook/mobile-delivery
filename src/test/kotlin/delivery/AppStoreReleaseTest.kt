package delivery

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class AppStoreReleaseTest {
    private val wednesday = clock
    private val tuesday = Clock.fixed(Instant.parse("2026-09-15T15:00:00Z"), clock.zone)
    private val thursday = Clock.fixed(Instant.parse("2026-09-17T04:30:00Z"), clock.zone)
    private val enabled =
        mapOf(
            "IOS_APPSTORE_ENABLED" to "true",
            "GITHUB_REPOSITORY" to "owner/repo",
            "GITHUB_WORKFLOW_REF" to "owner/repo/.github/workflows/release-it.yml@refs/heads/trunk",
        )
    private val scheduled = ReleaseRequest(scheduled = true, reason = null, phased = true)
    private val manual = ReleaseRequest(scheduled = false, reason = "Crash fix", phased = true)

    private val workspaces = mutableListOf<Path>()

    @AfterTest fun removeWorkspaces() = workspaces.forEach { it.toFile().deleteRecursively() }

    private inner class Store {
        val apple = FakeApple()
        val tags = FakeTags()
        val source = FakeSource().apply { shas = emptyList() }
        val holds = mutableListOf<HoldIssue>()
        val artifacts = FakeArtifacts()
        val releases = FakeReleases()
        private val workspace = createTempDirectory("release-symbols-").also(workspaces::add)

        fun symbols() = ReleaseSymbols(artifacts.forRun("release"), releases, workspace)

        fun build(
            number: String,
            version: String = "26.09.02",
            state: String = "VALID",
            expired: Boolean = false,
            receipt: Boolean = true,
        ) =
            TestFlightBuild("build-$number", version, number, state, expired).also {
                source.shas += "sha-$number"
                apple.builds += it
                artifacts.retain(version, number, "sha-$number")
                if (receipt)
                    tags.tags["deploy/testflight/$version/$number"] =
                        "sha-$number" to
                            Receipt(
                                "sha-$number",
                                MarketingVersion(version),
                                BuildNumber(number),
                                it.id,
                            )
            }

        fun version(version: String, state: String, number: String?, rollout: Rollout? = null) =
            StoreVersion(
                    "id-$version",
                    version,
                    state,
                    number?.let { "build-$it" },
                    number,
                    rollout,
                )
                .also { apple.versions += it }

        fun submitted() = apple.versions.filter { it.state == "WAITING_FOR_REVIEW" }
    }

    // Today's shape: 26.09.01 shipped with 400.1, and newer builds carry 26.09.02.
    private fun released(): Store =
        Store().apply {
            build("400.1", version = "26.09.01")
            version("26.09.01", "READY_FOR_SALE", "400.1")
        }

    private fun Store.release(
        env: Map<String, String> = enabled,
        at: Clock = wednesday,
        holdIssues: HoldIssues = HoldIssues { holds.toList() },
    ) = AppStoreRelease(config, apple, tags, source, symbols(), env, at, holdIssues = holdIssues)

    @Test
    fun `foreign version history is excluded from comparisons and reported in preflight`() {
        val store = released()
        store.version("2026.06.02", "REJECTED", "9999.1")
        store.version("1.0", "READY_FOR_SALE", "9998.1")
        store.build("405.1")
        val facts = store.release().facts()
        assertEquals("26.09.01", facts.lastRelease?.version)
        assertEquals("405.1", selectBuild(facts)?.build?.number)
        val report =
            AppStorePreflight(store.release(), store.apple, store.symbols())
                .verify()
                .joinToString("\n")
        assertContains(report, "Ignored marketing versions outside YY.MM.RR: 2026.06.02, 1.0")
        assertEquals(
            "skipped: earlier submission of 2026.06.02 failed or is uncertain",
            store.release().promote(scheduled).outcome,
        )
    }

    @Test
    fun `the newest receipted build wins over older builds and newer commits not yet in TestFlight`() {
        val store = released()
        store.build("402.1")
        store.build("405.1")
        store.build("407.1", receipt = false) // uploaded but not yet available to testers
        val result = store.release().promote(scheduled)
        assertEquals("submitted 26.09.02/405.1 (phased)", result.outcome)
        assertEquals("26.09.02", result.version)
        assertEquals("405.1", result.build)
        assertEquals("build-405.1", store.submitted().single().buildId)
        assertTrue("submit/appstore/26.09.02/405.1" in store.tags.tags)
        assertEquals("sha-405.1", store.tags.tags.getValue("submit/appstore/26.09.02/405.1").first)
    }

    @Test
    fun `no newer build is a quiet healthy skip`() {
        val store = released()
        val result = store.release().promote(scheduled)
        assertEquals(
            "skipped: no TestFlight build is newer than the last App Store release",
            result.outcome,
        )
        assertNull(result.action)
        assertTrue(store.apple.calls.isEmpty())
    }

    @Test
    fun `expired invalid and already used versions are ineligible`() {
        val store = released()
        store.build("402.1", state = "INVALID")
        store.build("403.1", expired = true)
        store.build("404.1", version = "26.09.01") // shares the shipped marketing version
        assertEquals(
            "skipped: no eligible TestFlight build",
            store.release().promote(scheduled).outcome,
        )
        store.build("401.1")
        assertEquals(
            "submitted 26.09.02/401.1 (phased)",
            store.release().promote(scheduled).outcome,
        )
    }

    @Test
    fun `an unresolved previous release blocks the next one`() {
        val store = released()
        store.build("405.1")
        store.version("26.09.02", "IN_REVIEW", "405.1")
        store.build("406.1", version = "26.09.03")
        assertEquals(
            "skipped: 26.09.02 is still IN_REVIEW",
            store.release().promote(scheduled).outcome,
        )
        val failure = assertFails { store.release(at = tuesday).promote(manual) }
        assertEquals(
            "26.09.02 is still IN_REVIEW. Remove it from review in App Store Connect first.",
            failure.message,
        )
        assertTrue(store.apple.calls.isEmpty())
    }

    @Test
    fun `failed or uncertain submissions are retried only by an explicit promote`() {
        val store = released()
        store.build("405.1")
        store.apple.submitAccepted = false
        assertFails { store.release().promote(scheduled) }
        assertEquals("PREPARE_FOR_SUBMISSION", store.apple.versions.last().state)
        store.apple.submitAccepted = true
        val next = store.release().promote(scheduled)
        assertEquals("skipped: earlier submission of 26.09.02 failed or is uncertain", next.outcome)
        assertTrue(next.action!!.contains("dispatch promote with a reason"))
        assertEquals(1, store.apple.calls.count { it == "submit" })
        assertEquals("submitted 26.09.02/405.1 (phased)", store.release().promote(manual).outcome)
        assertEquals(1, store.apple.calls.count { it == "create" })
    }

    @Test
    fun `after a rejection the retry instructions start with cancelling the open submission`() {
        val store = released()
        store.build("405.1")
        store.release(at = tuesday).promote(manual)
        val submitted = store.submitted().single()
        store.apple.versions[store.apple.versions.indexOf(submitted)] =
            submitted.copy(state = "REJECTED")
        val action =
            store.release().sweep().single { it.outcome == "submission REJECTED" }.operatorAction!!
        assertContains(action, "cancel")
        val failure = assertFails { store.release(at = tuesday).promote(manual) }
        assertContains(failure.message!!, "UNRESOLVED_ISSUES")
        assertContains(store.release().promote(scheduled).action!!, "cancel")
        // The person cancels the rejected submission in App Store Connect.
        store.apple.submissions.clear()
        assertEquals(
            "submitted 26.09.02/405.1 (phased)",
            store.release(at = tuesday).promote(manual).outcome,
        )
    }

    @Test
    fun `a rejected first release is finished in App Store Connect, not by promote`() {
        // Live on 2026-10-11: 26.09.01, submitted by hand and never released, was rejected.
        val store = Store()
        store.build("404.1", version = "26.09.01")
        store.version("26.09.01", "REJECTED", "404.1")
        store.apple.submissions += "id-26.09.01" to true
        val actions = store.release().sweep().mapNotNull { it.operatorAction }
        assertEquals(2, actions.size, actions.toString())
        actions.forEach { action ->
            assertContains(action, "App Store Connect")
            assertContains(action, "after the first release")
            assertFalse("dispatch promote" in action, action)
            assertFalse("cancel" in action, action)
        }
        val failure = assertFails { store.release(at = tuesday).promote(manual) }
        assertFalse("dispatch promote" in failure.message!!, failure.message)
        assertTrue(store.apple.calls.isEmpty())
    }

    @Test
    fun `a submission cut off before it was sent blocks releases until a person resolves it`() {
        val store = released()
        store.build("405.1")
        store.apple.submissionCutOff = true
        assertFails { store.release().promote(scheduled) }
        store.apple.submissionCutOff = false
        val changes = store.apple.calls.toList()
        // Apple holds the unsent submission open: no release may stage or submit beside it.
        val failure = assertFails { store.release(at = tuesday).promote(manual) }
        assertContains(failure.message!!, "Submit or cancel it in App Store Connect")
        val skipped = store.release().promote(scheduled)
        assertContains(skipped.outcome, "review submission")
        assertEquals(changes, store.apple.calls)
        val finding =
            store.release().sweep().single {
                it.operatorAction?.contains("Submit or cancel it in App Store Connect") == true
            }
        assertIs<IncidentSubject.ReviewSubmission>(finding.operationKey)
    }

    @Test
    fun `a version added to an unsent submission is never reported submitted`() {
        val store = released()
        store.build("405.1")
        store.apple.submissionCutOff = true
        store.apple.unsentVersionState = "READY_FOR_REVIEW"
        assertFails { store.release().promote(scheduled) }
        assertFalse("submit/appstore/26.09.02/405.1" in store.tags.tags)
        store.apple.submissionCutOff = false
        store.release().sweep()
        assertFalse("submit/appstore/26.09.02/405.1" in store.tags.tags)
    }

    @Test
    fun `a lost submit response is confirmed from Apple and never repeated`() {
        val store = released()
        store.build("405.1")
        store.apple.failureAt = "submit"
        assertEquals(
            "submitted 26.09.02/405.1 (phased)",
            store.release().promote(scheduled).outcome,
        )
        assertEquals(1, store.apple.calls.count { it == "submit" })
        store.apple.failureAt = null
        assertEquals(
            "skipped: 26.09.02 is still WAITING_FOR_REVIEW",
            store.release().promote(scheduled).outcome,
        )
        assertEquals(1, store.apple.calls.count { it == "submit" })
    }

    @Test
    fun `scheduled runs use normal eligibility even when delayed past the scheduled day`() {
        for (day in listOf(tuesday, thursday)) {
            val store = released()
            store.build("405.1")
            assertEquals(
                "submitted 26.09.02/405.1 (phased)",
                store.release(at = day).promote(scheduled).outcome,
            )
            assertEquals(1, store.apple.calls.count { it == "submit" })
            assertEquals(
                "skipped: 26.09.02 is still WAITING_FOR_REVIEW",
                store.release(at = day).promote(scheduled).outcome,
            )
            assertEquals(1, store.apple.calls.count { it == "submit" })
        }
    }

    @Test
    fun `a manual release picks the newest eligible build`() {
        val store = released()
        store.build("402.1")
        store.build("405.1")
        val eligiblePick = selectBuild(store.release().facts())
        assertEquals(
            "submitted 26.09.02/405.1 (phased)",
            store.release(at = tuesday).promote(manual).outcome,
        )
        assertEquals(eligiblePick?.build?.id, store.submitted().single().buildId)
    }

    @Test
    fun `a fix can ship hours after that week's scheduled release`() {
        val store = released()
        store.build("405.1")
        store.release().promote(scheduled)
        // Apple approved and shipped 26.09.02 the same day; a fix follows as 26.09.03.
        store.apple.versions.replaceAll {
            if (it.version == "26.09.02") it.copy(state = "READY_FOR_SALE") else it
        }
        store.build("409.1", version = "26.09.03")
        assertEquals("submitted 26.09.03/409.1 (phased)", store.release().promote(manual).outcome)
    }

    @Test
    fun `manual releases need a reason and the enable switch`() {
        val store = released()
        store.build("405.1")
        assertEquals(
            "A manual App Store release needs a reason",
            assertFails { store.release().promote(manual.copy(reason = " ")) }.message,
        )
        assertFails {
            store.release(env = enabled + ("IOS_APPSTORE_ENABLED" to "false")).promote(manual)
        }
        assertFails { store.release(env = enabled - "IOS_APPSTORE_ENABLED").promote(scheduled) }
        assertTrue(store.apple.calls.isEmpty())
    }

    @Test
    fun `releases request a phased rollout unless the fix goes to everyone`() {
        val phased = released().apply { build("405.1") }
        phased.release().promote(scheduled)
        assertEquals(listOf("create", "stage", "phase", "submit"), phased.apple.calls)
        val everyone = released().apply { build("405.1") }
        // A reused draft left with a phased release request loses it for rollout=all.
        everyone.version(
            "26.09.02",
            "PREPARE_FOR_SUBMISSION",
            null,
            Rollout("old", "INACTIVE", null, 0),
        )
        assertEquals(
            "submitted 26.09.02/405.1 (all users)",
            everyone.release().promote(manual.copy(phased = false)).outcome,
        )
        assertEquals(listOf("stage", "unphase", "submit"), everyone.apple.calls)
        assertNull(everyone.submitted().single().rollout)
    }

    @Test
    fun `releases wait until the first version has been released by hand`() {
        val store = Store()
        store.build("405.1")
        store.version("1.0", "PREPARE_FOR_SUBMISSION", null)
        val skipped = store.release().promote(scheduled)
        assertEquals("skipped: no App Store version has been released yet", skipped.outcome)
        assertTrue(skipped.action!!.contains("Release the first one by hand"))
        val stopped = assertFailsWith<IllegalStateException> { store.release().promote(manual) }
        assertTrue(stopped.message!!.contains("Release the first one by hand"))
        assertEquals(emptyList(), store.apple.calls)
    }

    @Test
    fun `a draft a person made in App Store Connect is never taken over`() {
        val store = released()
        store.build("405.1")
        store.version("27.0", "PREPARE_FOR_SUBMISSION", null)
        val skipped = store.release().promote(scheduled)
        assertEquals(
            "skipped: App Store version 27.0 is a draft this pipeline did not make",
            skipped.outcome,
        )
        assertContains(skipped.action!!, "27.0")
        val failure = assertFails { store.release(at = tuesday).promote(manual) }
        assertContains(failure.message!!, "27.0")
        assertEquals(emptyList(), store.apple.calls)
        assertTrue(store.releases.writes.isEmpty())
        assertEquals("27.0", store.apple.versions.last().version)
    }

    @Test
    fun `a draft left by an interrupted release is reused for a newer build`() {
        val store = released()
        store.build("405.1")
        store.build("410.1", version = "26.09.03")
        // An earlier run created 26.09.02 and stopped before staging 405.1.
        store.version("26.09.02", "PREPARE_FOR_SUBMISSION", null)
        assertEquals(
            "submitted 26.09.03/410.1 (phased)",
            store.release().promote(scheduled).outcome,
        )
        assertEquals(listOf("rename", "stage", "phase", "submit"), store.apple.calls)
        // A reused draft still takes its metadata from the last release.
        assertEquals("26.09.01", store.apple.stagedTemplate)
    }

    @Test
    fun `rollout controls make exactly one Apple change and only from a dispatch`() {
        val store = released()
        store.apple.versions.replaceAll { it.copy(rollout = Rollout("rollout-1", "ACTIVE", 3, 0)) }
        val release = store.release()
        assertFails { release.control("pause", " ") }
        assertTrue(store.apple.calls.isEmpty())
        assertEquals(
            "26.09.01 rollout set to PAUSED: Crash spike",
            release.control("pause", "Crash spike").outcome,
        )
        assertEquals(listOf("rollout:PAUSED"), store.apple.calls)
        release.control("resume", "Fix confirmed")
        release.control("release-to-all", "Fix confirmed")
        assertEquals(
            listOf("rollout:PAUSED", "rollout:ACTIVE", "rollout:COMPLETE"),
            store.apple.calls,
        )
        assertEquals(
            "The phased rollout of 26.09.01 is COMPLETE; cannot pause it",
            assertFails { release.control("pause", "Crash spike") }.message,
        )
        assertFails { store.release(env = emptyMap()).control("resume", "Fix confirmed") }
        assertEquals(3, store.apple.calls.size)
    }

    @Test
    fun `repeating a rollout change already in effect changes nothing`() {
        val store = released()
        store.apple.versions.replaceAll { it.copy(rollout = Rollout("rollout-1", "ACTIVE", 3, 0)) }
        val release = store.release()
        release.control("pause", "Crash spike")
        assertEquals(
            "26.09.01 rollout is already PAUSED; nothing changed: Crash spike",
            release.control("pause", "Crash spike").outcome,
        )
        release.control("resume", "Fix confirmed")
        assertContains(release.control("resume", "Fix confirmed").outcome, "already ACTIVE")
        release.control("release-to-all", "Fix confirmed")
        assertContains(release.control("release-to-all", "Again").outcome, "already COMPLETE")
        assertEquals(
            listOf("rollout:PAUSED", "rollout:ACTIVE", "rollout:COMPLETE"),
            store.apple.calls,
        )
    }

    @Test
    fun `a sweep reports rollout state and never changes Apple`() {
        val store = released()
        store.apple.versions.replaceAll { it.copy(rollout = Rollout("rollout-1", "PAUSED", 4, 2)) }
        val findings = store.release().sweep()
        assertEquals(
            SweepFinding(
                IncidentSubject.AppStoreVersion("26.09.01"),
                "phased rollout paused, day 4 (10%), pause days used 2/30",
            ),
            findings.single(),
        )
        assertTrue(store.apple.calls.isEmpty())
        // Receipts for the shipped version are repaired from Apple facts alone.
        assertTrue("submit/appstore/26.09.01/400.1" in store.tags.tags)
        assertTrue("deploy/appstore/26.09.01/400.1" in store.tags.tags)
        store.release().sweep()
        assertEquals(2, store.tags.tags.keys.count { it.contains("appstore") })
    }

    @Test
    fun `a submission whose receipt cannot be written still counts and asks for repair`() {
        val store = released()
        store.build("405.1")
        store.tags.failWrites = true
        val result = store.release().promote(scheduled)
        assertEquals("submitted 26.09.02/405.1 (phased)", result.outcome)
        assertTrue(result.action!!.contains("submit/appstore/26.09.02/405.1 was not written"))
    }

    @Test
    fun `a change between selection and submission stops before Apple is touched`() {
        val store = released()
        store.build("405.1")
        var reads = 0
        val apple =
            object : Apple by store.apple {
                override fun storeVersions(): List<StoreVersion> {
                    // Someone submits another version from App Store Connect meanwhile.
                    if (++reads == 2) store.version("26.09.02", "WAITING_FOR_REVIEW", "405.1")
                    return store.apple.storeVersions()
                }
            }
        assertFails {
            AppStoreRelease(
                    config,
                    apple,
                    store.tags,
                    FakeSource(),
                    store.symbols(),
                    enabled,
                    wednesday,
                )
                .promote(scheduled)
        }
        assertTrue(store.apple.calls.isEmpty())
    }

    private fun hold(
        number: Int,
        build: String,
        open: Boolean = true,
        fix: String? = null,
        bot: Boolean = false,
    ) =
        HoldIssue(
            number,
            open,
            "### Build\n\n$build\n\n### Reason\n\nCrash on launch",
            fix,
            closedByPerson = !open && !bot,
        )

    @Test
    fun `issue form bodies name the held build`() {
        assertEquals("26.09.02/405.1", heldBuild(hold(1, "26.09.02/405.1").body))
        // As TestFlight shows it, and as a browser may send the form.
        assertEquals("26.09.02/405.1", heldBuild(hold(1, "26.09.02 (405.1)").body))
        assertEquals("26.09.02/405.1", heldBuild("### Build\r\n\r\n 26.09.02 (405.1) \r\n"))
        assertNull(heldBuild("### Build\n\n26.09.02 (405.1"))
        assertNull(heldBuild("### Build\n\nthe latest one"))
        assertNull(heldBuild("No form here"))
    }

    @Test
    fun `a hold typed the way TestFlight shows the build still holds it`() {
        val store = released()
        store.build("402.1")
        store.build("405.1")
        store.holds += hold(42, "26.09.02 (405.1)")
        assertEquals(
            "submitted 26.09.02/402.1 (phased)",
            store.release().promote(scheduled).outcome,
        )
    }

    @Test
    fun `a held build is skipped for an older one still newer than the last release`() {
        val store = released()
        store.build("402.1")
        store.build("405.1")
        store.holds += hold(42, "26.09.02/405.1")
        assertEquals(
            "submitted 26.09.02/402.1 (phased)",
            store.release().promote(scheduled).outcome,
        )
    }

    @Test
    fun `later builds stay held until one contains the fix`() {
        val store = released()
        store.build("402.1")
        store.build("405.1")
        store.holds += hold(42, "26.09.02/402.1")
        assertEquals(
            "skipped: 26.09.02/405.1 is held by #42",
            store.release().promote(scheduled).outcome,
        )
        // Fixed by a commit after 405.1: 405.1 still lacks the fix, 407.1 has it.
        store.source.shas += "sha-fix"
        store.build("407.1")
        store.holds[0] = hold(42, "26.09.02/402.1", open = false, fix = "sha-fix")
        assertEquals(
            "submitted 26.09.02/407.1 (phased)",
            store.release().promote(scheduled).outcome,
        )
    }

    @Test
    fun `a false alarm closed by a person lifts the hold and a bot cannot`() {
        val botClosed =
            released().apply {
                build("405.1")
                holds += hold(42, "26.09.02/405.1", open = false, bot = true)
            }
        assertEquals(
            "skipped: 26.09.02/405.1 is held by #42",
            botClosed.release().promote(scheduled).outcome,
        )
        val personClosed =
            released().apply {
                build("405.1")
                holds += hold(42, "26.09.02/405.1", open = false)
            }
        assertEquals(
            "submitted 26.09.02/405.1 (phased)",
            personClosed.release().promote(scheduled).outcome,
        )
    }

    @Test
    fun `held release links its issue and explains how to lift it`() {
        for (open in listOf(true, false)) {
            val store = released()
            store.build("405.1")
            store.holds += hold(42, "26.09.02/405.1", open = open, bot = !open)
            val result = store.release().promote(scheduled)
            assertEquals("skipped: 26.09.02/405.1 is held by #42", result.outcome)
            val action = assertNotNull(result.action)
            assertContains(action, "https://github.com/owner/repo/issues/42")
            assertContains(
                action,
                if (open) "is open" else "closed by a bot or unknown actor without a fix",
            )
            assertContains(action, "false alarm")
            assertContains(action, "close it yourself")
            assertContains(action, "commit that fixes it")
            assertTrue(store.apple.calls.isEmpty())
        }
    }

    private fun oldClosedHold(events: String): HoldIssues {
        val commands = RecordingCommands()
        commands.respond = { call ->
            val path = call.args[2]
            val body =
                when {
                    "/events?" in path -> events
                    "since=" in path || "state=open" in path -> "[]"
                    else ->
                        """[{"number":42,"state":"closed","closed_at":"2026-01-31T00:00:00Z","body":"### Build\n\n26.01.01/402.1"}]"""
                }
            CommandResult(0, body, "")
        }
        return GitHubHoldIssues(GitHub("owner/repo", commands, emptyMap()))
    }

    @Test
    fun `old bot and unknown closes still block fresh descendants of expired held builds`() {
        for (events in
            listOf(
                """[{"event":"closed","actor":{"type":"Bot"}}]""",
                """[{"event":"closed","actor":null}]""",
                "[]",
            )) {
            val store = released()
            store.build("402.1", version = "26.01.01", expired = true)
            store.build("405.1")
            assertEquals(
                "skipped: 26.09.02/405.1 is held by #42",
                store.release(holdIssues = oldClosedHold(events)).promote(scheduled).outcome,
            )
            assertTrue(store.submitted().isEmpty())
        }
    }

    @Test
    fun `an old fix still blocks a new attempt of a commit before the fix`() {
        val store = released()
        store.build("402.1", version = "26.01.01", expired = true)
        store.build("405.1", expired = true)
        store.source.shas += "sha-fix"
        store.build("405.2")
        store.tags.tags["deploy/testflight/26.09.02/405.2"] =
            "sha-405.1" to store.tags.tags.getValue("deploy/testflight/26.09.02/405.2").second
        val holds =
            oldClosedHold("""[{"event":"closed","commit_id":"sha-fix","actor":{"type":"User"}}]""")
        assertEquals(
            "skipped: 26.09.02/405.2 is held by #42",
            store.release(holdIssues = holds).promote(scheduled).outcome,
        )
        assertTrue(store.submitted().isEmpty())
        store.build("407.1")
        assertEquals(
            "submitted 26.09.02/407.1 (phased)",
            store.release(holdIssues = holds).promote(scheduled).outcome,
        )
    }

    @Test
    fun `an old false alarm closed by a person remains lifted`() {
        val store = released()
        store.build("402.1", version = "26.01.01", expired = true)
        store.build("405.1")
        val holds = oldClosedHold("""[{"event":"closed","actor":{"type":"User"}}]""")
        assertEquals(
            "submitted 26.09.02/405.1 (phased)",
            store.release(holdIssues = holds).promote(scheduled).outcome,
        )
    }

    @Test
    fun `a hold that names no receipted build blocks every release`() {
        for (body in listOf("26.09.02/999.1", "latest")) {
            val store = released()
            store.build("405.1")
            store.holds += HoldIssue(7, true, "### Build\n\n$body\n\n### Reason\n\nCrash")
            val skipped = store.release().promote(scheduled)
            assertEquals("skipped: hold #7 cannot be resolved", skipped.outcome)
            assertContains(skipped.action!!, "Hold #7")
            // The message quotes what was typed, never claiming the field is missing.
            assertContains(skipped.action!!, "\"$body\"")
            assertContains(
                store
                    .release()
                    .sweep()
                    .single { it.operationKey == IncidentSubject.Hold(7) }
                    .operatorAction!!,
                "\"$body\"",
            )
            assertFails { store.release(at = tuesday).promote(manual) }
            assertEquals(
                "unresolvable hold",
                store
                    .release()
                    .sweep()
                    .single { it.operationKey == IncidentSubject.Hold(7) }
                    .outcome,
            )
            assertTrue(store.apple.calls.isEmpty())
        }
    }

    @Test
    fun `a hold arriving between selection and submission stops the submission`() {
        val store = released()
        store.build("405.1")
        var reads = 0
        val release =
            AppStoreRelease(
                config,
                store.apple,
                store.tags,
                store.source,
                store.symbols(),
                enabled,
                wednesday,
            ) {
                if (++reads == 2) listOf(hold(42, "26.09.02/405.1")) else emptyList()
            }
        assertFails { release.promote(scheduled) }
        assertTrue(store.apple.calls.isEmpty())
    }

    @Test
    fun `a hold filed while the dSYMs are kept stops the submission`() {
        val store = released()
        store.build("405.1")
        // Keeping symbols downloads the whole retained build; a tester can file a hold meanwhile.
        val releases =
            object : ReleaseAssets by store.releases {
                override fun create(tag: String, title: String, notes: String, files: List<Path>) {
                    store.holds += hold(42, "26.09.02/405.1")
                    store.releases.create(tag, title, notes, files)
                }
            }
        val workspace = createTempDirectory("release-symbols-").also(workspaces::add)
        val symbols = ReleaseSymbols(store.artifacts.forRun("release"), releases, workspace)
        val release =
            AppStoreRelease(
                config,
                store.apple,
                store.tags,
                store.source,
                symbols,
                enabled,
                wednesday,
            ) {
                store.holds.toList()
            }
        assertFails { release.promote(scheduled) }
        assertEquals(listOf("create"), store.releases.writes)
        assertTrue(store.apple.calls.isEmpty())
    }

    @Test
    fun `a hold on a build already in review or rolling out asks a person to act`() {
        val review = released()
        review.build("405.1")
        review.version("26.09.02", "IN_REVIEW", "405.1")
        review.holds += hold(42, "26.09.02/405.1")
        val inReview = review.release().sweep().single { it.outcome == "held" }
        assertContains(inReview.operatorAction!!, "Remove it from review in App Store Connect")
        val rollout = Store()
        rollout.build("405.1")
        rollout.version("26.09.02", "READY_FOR_SALE", "405.1", Rollout("r", "ACTIVE", 2, 0))
        rollout.holds += hold(42, "26.09.02/405.1")
        val rolling = rollout.release().sweep().single { it.outcome == "held" }
        assertContains(
            rolling.operatorAction!!,
            "gh workflow run release-it.yml -f operation=pause -f reason=\"Hold #",
        )
        assertTrue(review.apple.calls.isEmpty() && rollout.apple.calls.isEmpty())
    }

    @Test
    fun `release notes skip a version shipped from a build without a receipt`() {
        val store = released()
        // 26.09.02 went out by hand, from a build the pipeline never delivered.
        store.version("26.09.02", "READY_FOR_SALE", "401.1")
        store.build("405.1", version = "26.09.03")
        assertEquals(
            "submitted 26.09.03/405.1 (phased)",
            store.release().promote(scheduled).outcome,
        )
        assertEquals(listOf("sha-400.1"), store.source.notesSince)
        // With no released version from a receipted build, notes start at the baseline.
        val unreceipted =
            Store().apply {
                build("400.1", version = "26.09.01", receipt = false)
                version("26.09.01", "READY_FOR_SALE", "400.1")
                build("405.1")
            }
        unreceipted.release().promote(scheduled)
        assertEquals(listOf(config.baseline), unreceipted.source.notesSince)
    }

    @Test
    fun `a failed read before submission leaves Apple untouched`() {
        val store = released()
        store.build("405.1")
        store.tags.unreadable += "deploy/testflight/26.09.01/400.1"
        assertContains(assertFails { store.release().promote(scheduled) }.message!!, "HTTP 502")
        assertTrue(store.apple.calls.isEmpty())
    }

    @Test
    fun `a failed receipt read stops the release instead of misreading a hold`() {
        for (held in
            listOf(hold(42, "26.09.02/402.1"), hold(42, "26.09.02/402.1", false, "sha-fix"))) {
            val store = released()
            store.build("402.1")
            store.source.shas += "sha-fix"
            store.build("405.1")
            store.holds += held
            store.tags.failReads = true
            // Not "cannot be resolved" (open) and not lifted (closed with a fix): red.
            val failure = assertFails { store.release().promote(scheduled) }
            assertContains(failure.message!!, "HTTP 502")
            assertTrue(store.apple.calls.isEmpty() && store.releases.writes.isEmpty())
        }
    }

    @Test
    fun `a closed hold with no record of a person closing it stays in force`() {
        val store = released()
        store.build("405.1")
        store.holds += HoldIssue(42, false, "### Build\n\n26.09.02/405.1", closedByPerson = false)
        assertEquals(
            "skipped: 26.09.02/405.1 is held by #42",
            store.release().promote(scheduled).outcome,
        )
    }

    @Test
    fun `rollout controls read only Apple versions`() {
        val store = released()
        store.apple.versions.replaceAll { it.copy(rollout = Rollout("rollout-1", "ACTIVE", 3, 0)) }
        store.tags.failReads = true
        val release =
            AppStoreRelease(
                config,
                store.apple,
                store.tags,
                store.source,
                store.symbols(),
                enabled,
            ) {
                throw CommandError("GitHub issues unavailable")
            }
        release.control("pause", "Crash spike")
        assertEquals(listOf("rollout:PAUSED"), store.apple.calls)
        assertEquals(listOf("versions"), store.apple.reads)
        assertEquals(0, store.tags.commitReads)
    }

    @Test
    fun `selection reads a build's commit only when a hold could block it, and once`() {
        val store = released()
        listOf("402.1", "403.1", "405.1").forEach { store.build(it) }
        val release = store.release()
        assertEquals(
            "405.1",
            selectBuild(release.facts(), release.rules(release.facts()))?.build?.number,
        )
        assertEquals(0, store.tags.commitReads)
        store.holds += hold(42, "26.09.02/405.1")
        val facts = release.facts()
        val reads = store.tags.commitReads // resolving the hold itself
        val selection = Selection(facts, release.rules(facts))
        assertEquals("403.1", selection.selected?.build?.number)
        assertEquals("is not held", selection.failure(selection.candidates.first()))
        // 405.1 and 403.1 were checked for the hold once each; the older 402.1 never was.
        assertEquals(2, store.tags.commitReads - reads)
    }

    @Test
    fun `preflight explains the first rule each of the newest builds fails`() {
        val store = released()
        store.build("402.1")
        store.build("405.1")
        store.build("407.1", receipt = false)
        store.holds += hold(42, "26.09.02/405.1")
        val lines = AppStorePreflight(store.release(), store.apple, store.symbols()).verify()
        assertContains(lines, "Would select 26.09.02/402.1")
        assertContains(lines, "26.09.02/407.1: fails: has a TestFlight receipt")
        assertContains(lines, "26.09.02/405.1: fails: is not held")
        assertContains(lines, "26.09.02/402.1: eligible")
        assertTrue(store.apple.calls.isEmpty() && store.releases.writes.isEmpty())
    }

    @Test
    fun `preflight warns about every bot closed hold on a receipted commit`() {
        val store = released()
        store.build("402.1", expired = true)
        store.build("405.1")
        store.holds += hold(42, "26.09.02/402.1", open = false, bot = true)
        store.holds += hold(43, "26.09.02/405.1", open = false, bot = true)
        val lines = AppStorePreflight(store.release(), store.apple, store.symbols()).verify()
        for (number in listOf(42, 43)) {
            val warning = lines.single { it.startsWith("Blocked: Hold #$number ") }
            assertContains(warning, "https://github.com/owner/repo/issues/$number")
            assertContains(warning, "closed by a bot or unknown actor without a fix")
            assertContains(warning, "reopen it and close it yourself")
            assertContains(warning, "commit that fixes it")
        }
        assertTrue(store.apple.calls.isEmpty() && store.releases.writes.isEmpty())
    }

    @Test
    fun `preflight does not warn about lifted fixed or unreceipted closed holds`() {
        val store = released()
        store.build("405.1")
        store.holds += hold(42, "26.09.02/405.1", open = false)
        store.holds += hold(43, "26.09.02/405.1", open = false, fix = "sha-fix", bot = true)
        store.holds += hold(44, "26.09.02/999.1", open = false, bot = true)
        val lines = AppStorePreflight(store.release(), store.apple, store.symbols()).verify()
        assertFalse(lines.any { it.startsWith("Blocked: Hold #") }, lines.toString())
    }

    @Test
    fun `preflight reports an open review submission that would stop promote`() {
        val store = released()
        store.build("405.1")
        store.apple.submissions += "id-26.09.02" to false
        val lines = AppStorePreflight(store.release(), store.apple, store.symbols()).verify()
        assertContains(
            lines,
            "Blocked: review submission submission-0 is READY_FOR_REVIEW. Submit or cancel it in App Store Connect.",
        )
    }

    @Test
    fun `preflight reports that the first release is made by hand instead of failing`() {
        val store = Store()
        store.build("405.1")
        store.version("1.0", "PREPARE_FOR_SUBMISSION", null)
        val lines = AppStorePreflight(store.release(), store.apple, store.symbols()).verify()
        assertContains(lines, "Would select 26.09.02/405.1")
        assertContains(
            lines,
            "Blocked: no App Store version has been released yet; release the first by hand",
        )
        assertTrue(store.apple.calls.isEmpty())
    }

    @Test
    fun `a submission keeps the build's dSYMs on its receipt tag`() {
        val store = released()
        store.build("405.1")
        assertEquals(
            "submitted 26.09.02/405.1 (phased)",
            store.release().promote(scheduled).outcome,
        )
        val kept = store.releases.releases.getValue("deploy/testflight/26.09.02/405.1")
        assertEquals(setOf("manifest.json", "symbols.zip"), kept.keys)
        assertEquals("dsym-405.1", kept.getValue("symbols.zip").decodeToString())
        assertContains(kept.getValue("manifest.json").decodeToString(), "sha-405.1")
        // A second submission of the same build finds its symbols already kept.
        store.apple.versions.removeIf { it.version == "26.09.02" }
        store.release().promote(manual)
        assertEquals(listOf("create"), store.releases.writes)
    }

    @Test
    fun `a build whose dSYMs cannot be kept is not submitted`() {
        for (problem in listOf("expired", "different")) {
            val store = released()
            store.build("405.1")
            when (problem) {
                "expired" -> store.artifacts.expired += "ios-build-405.1"
                "different" ->
                    store.releases.releases["deploy/testflight/26.09.02/405.1"] =
                        mutableMapOf("symbols.zip" to "other".toByteArray())
            }
            val failure = assertFails { store.release().promote(scheduled) }
            assertContains(failure.message!!, if (problem == "expired") "expired" else "different")
            assertTrue(store.apple.calls.isEmpty(), problem)
        }
    }

    @Test
    fun `keeping dSYMs survives lost responses and finishes a partial Release`() {
        val lost = released().apply { build("405.1") }
        lost.releases.lostResponses = true
        assertEquals("submitted 26.09.02/405.1 (phased)", lost.release().promote(scheduled).outcome)
        val partial = released().apply { build("405.1") }
        val manifest = partial.artifacts.retained.getValue("ios-build-405.1").second
        partial.releases.releases["deploy/testflight/26.09.02/405.1"] =
            mutableMapOf("manifest.json" to manifest.getValue("manifest.json"))
        partial.release().promote(scheduled)
        assertEquals(listOf("upload symbols.zip"), partial.releases.writes)
    }

    @Test
    fun `preflight says whether the selected build's dSYMs can be kept`() {
        val store = released()
        store.build("405.1")
        val lines = AppStorePreflight(store.release(), store.apple, store.symbols()).verify()
        assertContains(lines, "Its dSYMs are retained and can be kept")
        store.artifacts.expired += "ios-build-405.1"
        val blocked = AppStorePreflight(store.release(), store.apple, store.symbols()).verify()
        assertTrue(
            blocked.any { it.startsWith("Blocked: Actions has expired") },
            blocked.toString(),
        )
        assertTrue(store.apple.calls.isEmpty() && store.releases.writes.isEmpty())
    }
}
