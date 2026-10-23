package delivery

import java.time.Clock
import java.time.Instant
import kotlin.test.*
import org.junit.Test

class BetaDeliveryTest {
    private val sha = "source-sha"

    private fun World.receipt(number: String = "234.1") =
        tags.tags["deploy/testflight/26.09.01/$number"]

    private fun World.retainedBytes(number: String = "234.1") =
        artifacts.retained.getValue("ios-build-$number").second.mapValues { it.value.copyOf() }

    private fun World.assertRetainedBytes(
        expected: Map<String, ByteArray>,
        number: String = "234.1",
    ) {
        val actual = artifacts.retained.getValue("ios-build-$number").second
        assertEquals(expected.keys, actual.keys, "Retained package files for $number")
        expected.forEach { (name, bytes) ->
            assertContentEquals(bytes, actual.getValue(name), "Retained $name for $number")
        }
    }

    private fun World.assertDelivered(number: String = "234.1") {
        val (commit, receipt) = assertNotNull(receipt(number))
        assertEquals(sha, commit)
        assertEquals(sha, receipt.sha)
        assertEquals(MarketingVersion("26.09.01"), receipt.version)
        assertEquals(BuildNumber(number), receipt.number)
        assertEquals("build-$number", receipt.ascBuildId)
        assertTrue(receipt.ascBuildId in apple.testers, "Receipt requires a testable build")
    }

    @Test
    fun `a push delivers its exact commit once and writes the receipt after testers can install`() =
        temp { output ->
            val world = World(output)
            world.source.shas = (1..40).map { "commit-$it" }
            val tip = world.source.shas.last()
            world.deliver(tip)
            assertEquals(listOf(tip), world.builds.verified)
            assertEquals(listOf(tip), world.builds.archived)
            assertEquals(listOf("upload", "distribute"), world.apple.calls)
            val (target, receipt) = world.tags.tags.getValue("deploy/testflight/26.09.01/273.1")
            assertEquals(tip, target)
            assertEquals("build-273.1", receipt.ascBuildId)
            assertEquals(
                "Commit $tip",
                world.apple.notesById.getValue("build-273.1").lines().last(),
            )
            assertIs<StartDecision.AlreadyDelivered>(world.beta().start(Operation.Deliver(tip)))
            world.deliver(tip)
            assertEquals(1, world.builds.archived.size)
            assertEquals(listOf("upload", "distribute"), world.apple.calls)
        }

    @Test
    fun `a delivered receipt does not bypass retry evidence validation`() = temp { output ->
        val world = World(output)
        world.deliver(sha)
        val stale = assertFails {
            world.job(Operation.Retry(sha, "none", "Retry acceptance check"))
        }
        assertContains(stale.message!!, "Stale retry: the latest build")
        assertFails { world.job(Operation.Retry(sha, "234.1", " ")) }
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
        assertEquals(listOf(sha), world.builds.archived)
        world.assertDelivered()
    }

    @Test
    fun `an explicit new attempt delivers again and duplicate dispatches are stale`() =
        temp { output ->
            val world = World(output)
            world.deliver(sha)
            val retry = Operation.Retry(sha, "234.1", "Rebuild delivered commit", newAttempt = true)
            world.job(retry, run = "new-attempt")
            world.assertDelivered("234.2")
            world.assertDelivered("234.1")
            assertContains(assertFails { world.job(retry) }.message!!, "Stale retry")
            world.deliver(sha)
            world.job(Operation.Rerun(sha, "234.1", newAttempt = true), run = "new-attempt")
            assertEquals(listOf("234.1", "234.2"), world.apple.uploadedNumbers)
            assertEquals(2, world.builds.archived.size)
        }

    @Test
    fun `a new attempt requires the latest attempt to be delivered`() = temp { output ->
        val world = World(output)
        val refused = world.beta().start(Operation.Retry(sha, "none", "New", newAttempt = true))
        assertIs<StartDecision.Refused>(refused)
        assertIs<StartDecision.Refused>(
            world.beta().start(Operation.Rerun(sha, "none", newAttempt = true))
        )
        world.apple.distributionReachesTesters = false
        assertFails { world.deliver(sha) }
        assertIs<StartDecision.Refused>(
            world.beta().start(Operation.Retry(sha, "234.1", "New", newAttempt = true))
        )
        assertIs<StartDecision.Refused>(
            world.beta().start(Operation.Rerun(sha, "234.1", newAttempt = true))
        )
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
    }

    @Test
    fun `an interrupted new attempt resumes despite the previous receipt`() = temp { output ->
        for (boundary in
            listOf("before-upload", "after-upload", "before-distribute", "after-distribute")) {
            val world = World(output.resolve(boundary))
            world.deliver(sha)
            world.apple.interrupt = { if (it == boundary) throw ProcessInterrupted() }
            assertFailsWith<ProcessInterrupted> {
                world.job(
                    Operation.Retry(sha, "234.1", "Rebuild", newAttempt = true),
                    run = "retry-run",
                )
            }
            val bytes = world.retainedBytes("234.2")
            world.apple.interrupt = null
            assertIs<StartDecision.Refused>(world.beta("old-run").start(Operation.Rerun(sha)))
            world.job(Operation.Rerun(sha, "234.1", newAttempt = true), run = "retry-run")
            world.assertDelivered("234.2")
            world.assertRetainedBytes(bytes, "234.2")
            assertEquals(listOf("234.1", "234.2"), world.apple.uploadedNumbers, boundary)
            assertEquals(2, world.builds.archived.size, boundary)
        }
    }

    @Test
    fun `a new attempt rerun retains authority after verification fails`() = temp { output ->
        val world = World(output)
        world.deliver(sha)
        world.builds.verifyError = CommandError("Simulator did not boot")
        assertFails {
            world.job(
                Operation.Retry(sha, "234.1", "Rebuild", newAttempt = true),
                run = "retry-run",
            )
        }
        world.builds.verifyError = null
        world.job(Operation.Rerun(sha, "234.1", newAttempt = true), run = "retry-run")
        world.assertDelivered("234.2")
        assertEquals(listOf("234.1", "234.2"), world.apple.uploadedNumbers)
    }

    @Test
    fun `an older receipt does not hide an uncertain newer attempt`() = temp { output ->
        val world = World(output)
        world.deliver(sha)
        world.apple.uploadOutcome = "INVISIBLE"
        assertFails {
            world.job(
                Operation.Retry(sha, "234.1", "Rebuild", newAttempt = true),
                run = "retry-run",
            )
        }
        assertIs<StartDecision.Refused>(
            world.beta("retry-run").start(Operation.Rerun(sha, "234.1", newAttempt = true))
        )
        assertIs<StartDecision.Refused>(
            world.beta().start(Operation.Retry(sha, "234.2", "Recover"))
        )
        val status = world.sweep().run(sha).findings.first { it.operationKey == "internal/$sha" }
        assertContains(status.outcome, "uncertain")
        world.apple.builds += TestFlightBuild("build-234.2", "26.09.01", "234.2", "VALID", false)
        world.retry(sha)
        world.retry(sha) // Duplicate recovery cannot request another build.
        world.assertDelivered("234.2")
        assertEquals(listOf("234.1", "234.2"), world.apple.uploadedNumbers)
    }

    @Test
    fun `no receipt is written until Apple reports the build testable`() = temp { output ->
        val world = World(output)
        world.apple.distributionReachesTesters = false
        assertFails { world.deliver(sha) }
        assertNull(world.receipt())
        // A later sweep never distributes; a retry resumes distribution without uploading.
        world.sweep().run(sha)
        assertEquals(listOf("upload", "distribute"), world.apple.calls)
        world.apple.distributionReachesTesters = true
        world.retry(sha)
        assertEquals(listOf("upload", "distribute", "distribute"), world.apple.calls)
        assertEquals(sha, world.receipt()!!.first)
    }

    @Test
    fun `a retry does not redistribute a build whose notes differ only in line breaks`() =
        temp { output ->
            val world = World(output)
            world.apple.distributionReachesTesters = false
            assertFails { world.deliver(sha) }
            // Testers can install it now, and Apple shows the notes with CRLF breaks.
            val build = world.apple.builds.single()
            world.apple.testers += build.id
            world.apple.notesById[build.id] = testerNotes(config, sha).replace("\n", "\r\n")
            world.retry(sha)
            assertEquals(listOf("upload", "distribute"), world.apple.calls)
            assertEquals(sha, world.receipt()!!.first)
        }

    @Test
    fun `failed verification takes no build number and deliver may run it again`() =
        temp { output ->
            val world = World(output)
            world.builds.verifyError =
                VerificationFailure("native-ui", FailureKind.VALIDATION, "Assertion failed")
            assertFails { world.deliver(sha) }
            assertTrue(world.builds.archived.isEmpty())
            assertTrue(world.apple.calls.isEmpty())
            assertEquals("none", world.beta().traces(sha).expectedAttempt)
            world.builds.verifyError = null
            world.deliver(sha)
            assertEquals(2, world.builds.verified.size)
            assertNotNull(world.receipt())
        }

    @Test
    fun `a missing toolchain stops before verification`() = temp { output ->
        val world = World(output)
        world.builds.environmentError = CommandError("Expected Xcode 26.6")
        assertFails { world.deliver(sha) }
        assertTrue(world.builds.verified.isEmpty())
        assertTrue(world.apple.calls.isEmpty())
    }

    @Test
    fun `corrupt retained bytes never reach Apple`() = temp { output ->
        val world = World(output)
        world.artifacts.corrupt = true
        assertFails { world.deliver(sha) }
        assertTrue(world.apple.calls.isEmpty())
    }

    @Test
    fun `a build TestFlight will never make testable says how to get a new number`() =
        temp { output ->
            val world = World(output)
            world.apple.distributionReachesTesters = false
            assertFails { world.deliver(sha) }
            world.apple.terminal["build-234.1"] = "PROCESSING_EXCEPTION"
            val failure = assertFails { world.retry(sha) }
            assertContains(failure.message!!, "26.09.01/234.1")
            assertContains(failure.message!!, "PROCESSING_EXCEPTION")
            assertContains(failure.message!!, "Expire it in App Store Connect, then retry")
            assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
            assertNull(world.receipt())
        }

    @Test
    fun `a lost upload response resumes distribution without uploading again`() = temp { output ->
        val world = World(output)
        world.apple.loseResponseAfter("upload") {
            val failure = assertFails { world.job(Operation.Deliver(sha), run = "upload-run") }
            assertContains(failure.message!!, "uncertain")
        }
        val retained = world.retainedBytes()
        assertEquals("upload-run", world.artifacts.retained.getValue("ios-build-234.1").first)
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
        assertEquals("build-234.1", world.apple.builds.single().id)
        assertTrue(world.apple.testers.isEmpty())
        assertNull(world.receipt())
        assertIs<StartDecision.Refused>(world.beta("new-push").start(Operation.Deliver(sha)))
        world.job(Operation.Retry(sha, "234.1", "Resume accepted upload"), run = "retry-run")
        assertEquals(listOf(sha), world.builds.verified)
        assertEquals(listOf(sha), world.builds.archived)
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
        assertEquals(1, world.apple.calls.count { it == "distribute" })
        world.assertRetainedBytes(retained)
        world.assertDelivered()
    }

    @Test
    fun `an upload Apple has but does not show yet is never repeated`() = temp { output ->
        val world = World(output)
        // Apple lists the accepted upload, but its TestFlight build is not visible yet.
        world.apple.uploadOutcome = "INVISIBLE"
        assertFails { world.job(Operation.Deliver(sha), run = "upload-run") }
        val retained = world.retainedBytes()
        assertTrue(world.apple.uploads.single().received)
        assertTrue(world.apple.builds.isEmpty())
        assertNull(world.receipt())
        val refused = world.beta("retry-run").start(Operation.Retry(sha, "234.1", "Try again"))
        assertContains(assertIs<StartDecision.Refused>(refused).reason, "still processing")
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
        assertFalse("distribute" in world.apple.calls)
        // Once Apple shows the build, a retry finishes it with the same number and bytes.
        world.apple.builds += TestFlightBuild("build-234.1", "26.09.01", "234.1", "VALID", false)
        world.job(Operation.Retry(sha, "234.1", "Build now visible"), run = "retry-run")
        assertEquals(listOf(sha), world.builds.verified)
        assertEquals(listOf(sha), world.builds.archived)
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
        assertEquals(1, world.apple.calls.count { it == "distribute" })
        world.assertRetainedBytes(retained)
        world.assertDelivered()
    }

    @Test
    fun `rejected invalid and abandoned uploads are retried only with a new number`() =
        temp { output ->
            for (failure in listOf("rejected", "invalid", "abandoned")) {
                val world = World(output.resolve(failure))
                when (failure) {
                    "rejected" ->
                        world.apple.rejection = AppleUpload("", "", "FAILED", "90001", "Bad binary")
                    "invalid" -> world.apple.uploadOutcome = "INVALID"
                    "abandoned" ->
                        world.apple.uploads += AppleUpload("26.09.01", "234.1", "AWAITING_UPLOAD")
                }
                if (failure != "abandoned") assertFails { world.deliver(sha) }
                world.apple.rejection = null
                world.apple.uploadOutcome = "VALID"
                world.retry(sha)
                assertNotNull(world.receipt("234.2"), failure)
                assertNull(world.receipt("234.1"), failure)
            }
        }

    @Test
    fun `upload cooldown refuses delivery one millisecond before 24 hours`() = temp { output ->
        val rejectedAt = Instant.parse("2026-09-15T11:00:00Z")
        val at = Clock.fixed(Instant.parse("2026-09-16T10:59:59.999Z"), clock.zone)
        val world = World(output, at)
        world.apple.uploads +=
            AppleUpload(
                "26.09.01",
                "233.1",
                "FAILED",
                "90382",
                "Upload limit reached",
                rejectedAt.toString(),
            )
        val failure = assertFails { world.job(Operation.Deliver(sha), run = "cooldown-run") }
        assertContains(failure.message!!, "cooldown")
        assertTrue(world.builds.verified.isEmpty(), "Cooldown must stop before verification")
        assertTrue(world.builds.archived.isEmpty(), "Cooldown must not take a build number")
        assertTrue(world.artifacts.retained.isEmpty())
        assertTrue(world.apple.calls.isEmpty(), "Cooldown must prevent every Apple write")
        assertTrue(world.apple.uploadedNumbers.isEmpty())
        assertTrue(world.tags.tags.isEmpty(), "Cooldown must not write a receipt")
    }

    @Test
    fun `upload cooldown permits delivery exactly at 24 hours`() = temp { output ->
        val rejectedAt = Instant.parse("2026-09-15T11:00:00Z")
        val at = Clock.fixed(Instant.parse("2026-09-16T11:00:00Z"), clock.zone)
        val world = World(output, at)
        world.apple.uploads +=
            AppleUpload(
                "26.09.01",
                "233.1",
                "FAILED",
                "90382",
                "Upload limit reached",
                rejectedAt.toString(),
            )
        world.job(Operation.Deliver(sha), run = "cooldown-run")
        assertEquals(listOf(sha), world.builds.verified)
        assertEquals(listOf(sha), world.builds.archived)
        assertEquals("cooldown-run", world.artifacts.retained.getValue("ios-build-234.1").first)
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
        // Upload must precede distribution: distribution is what makes the receipt valid.
        assertEquals(listOf("upload", "distribute"), world.apple.calls)
        world.assertDelivered()
    }

    @Test
    fun `a retained build Apple never listed is uploaded again under its own number`() =
        temp { output ->
            val world = World(output)
            world.apple.interrupt = { if (it == "before-upload") throw ProcessInterrupted() }
            assertFailsWith<ProcessInterrupted> { world.deliver(sha) }
            world.apple.interrupt = null
            assertTrue("ios-build-234.1" in world.artifacts.retained)
            assertEquals("234.1", world.beta().traces(sha).expectedAttempt)
            assertIs<StartDecision.Refused>(world.beta().start(Operation.Deliver(sha)))
            world.retry(sha)
            assertEquals(1, world.builds.archived.size)
            assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
            assertNotNull(world.receipt("234.1"))
        }

    @Test
    fun `a build retained before its version closed keeps its version and number`() =
        temp { output ->
            val world = World(output)
            world.apple.interrupt = { if (it == "before-upload") throw ProcessInterrupted() }
            assertFailsWith<ProcessInterrupted> { world.deliver(sha) }
            world.apple.interrupt = null
            // Wednesday: 26.09.01 goes to review, so new builds take 26.09.02.
            world.apple.versions +=
                StoreVersion("v1", "26.09.01", "WAITING_FOR_REVIEW", "b", "200.1", null)
            assertEquals("234.1", world.beta().traces(sha).expectedAttempt)
            world.retry(sha)
            assertEquals(1, world.builds.archived.size)
            assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
            assertNotNull(world.receipt("234.1"))
        }

    @Test
    fun `a re-upload Apple refuses for a closed version takes the next number`() = temp { output ->
        val world = World(output)
        world.apple.interrupt = { if (it == "before-upload") throw ProcessInterrupted() }
        assertFailsWith<ProcessInterrupted> { world.deliver(sha) }
        world.apple.interrupt = null
        // 26.09.01 ships, so Apple takes no more builds for it.
        world.apple.versions += StoreVersion("v1", "26.09.01", "READY_FOR_SALE", "b", "200.1", null)
        world.apple.rejection =
            AppleUpload("", "", "FAILED", "90062", "The version is already approved")
        assertFails { world.retry(sha) }
        world.apple.rejection = null
        world.retry(sha)
        assertEquals(listOf("234.1", "234.2"), world.apple.uploadedNumbers)
        // The retained bytes went back under their own version; only 234.2 was built anew.
        assertTrue(world.apple.uploads.any { it.version == "26.09.01" && it.number == "234.1" })
        assertEquals(2, world.builds.archived.size)
        assertNotNull(world.tags.tags["deploy/testflight/26.09.02/234.2"])
        assertNull(world.tags.tags["deploy/testflight/26.09.02/234.1"])
    }

    @Test
    fun `an upload stops when Apple has the number under another version`() = temp { output ->
        val world = World(output)
        world.apple.interrupt = { if (it == "before-upload") throw ProcessInterrupted() }
        assertFailsWith<ProcessInterrupted> { world.job(Operation.Deliver(sha), run = "r1") }
        world.apple.interrupt = null
        world.apple.uploads += AppleUpload("26.09.02", "234.1", "FAILED", "90062", "Rejected")
        val failure = assertFails { world.beta("r1").upload(Operation.Deliver(sha), output) }
        assertContains(failure.message!!, "under 26.09.02")
        assertTrue(world.apple.uploadedNumbers.isEmpty())
    }

    @Test
    fun `an expired retained build keeps its number taken`() = temp { output ->
        val world = World(output)
        world.apple.interrupt = { if (it == "before-upload") throw ProcessInterrupted() }
        assertFailsWith<ProcessInterrupted> { world.deliver(sha) }
        world.apple.interrupt = null
        world.artifacts.expired += "ios-build-234.1"
        world.retry(sha)
        assertEquals(listOf("234.2"), world.apple.uploadedNumbers)
        assertNotNull(world.receipt("234.2"))
    }

    @Test
    fun `hard interruptions around upload and distribution never repeat accepted effects`() =
        temp { output ->
            for (boundary in listOf("after-upload", "before-distribute", "after-distribute")) {
                val world = World(output.resolve(boundary))
                world.apple.interrupt = { if (it == boundary) throw ProcessInterrupted() }
                assertFailsWith<ProcessInterrupted> { world.deliver(sha) }
                world.apple.interrupt = null
                world.sweep().run(sha)
                if (world.receipt() == null) world.retry(sha)
                assertEquals(1, world.apple.calls.count { it == "upload" }, boundary)
                assertNotNull(world.receipt(), boundary)
            }
        }

    @Test
    fun `a receipt write failure keeps the delivery and the sweep repairs it`() = temp { output ->
        val world = World(output)
        world.tags.failWrites = true
        world.deliver(sha)
        assertNull(world.receipt())
        world.tags.failWrites = false
        world.sweep().run(sha)
        assertEquals(sha, world.receipt()!!.first)
        assertEquals(1, world.apple.calls.count { it == "upload" })
    }

    @Test
    fun `an Actions rerun finishes its own delivery instead of being refused`() = temp { output ->
        val world = World(output)
        world.apple.distributionReachesTesters = false
        assertFails { world.job(Operation.Deliver(sha), run = "77") }
        world.apple.distributionReachesTesters = true
        world.job(Operation.Rerun(sha), run = "77")
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
        assertEquals(sha, world.receipt("234.1")!!.first)
    }

    @Test
    fun `an upload Apple has not listed yet is never replaced by a new build number`() =
        temp { output ->
            val world = World(output)
            // Apple accepts the number, loses the response and lists neither upload nor build.
            world.apple.uploadOutcome = "LOST"
            assertFails { world.job(Operation.Deliver(sha), run = "upload-run") }
            val retained = world.retainedBytes()
            assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
            assertTrue(world.apple.uploads.isEmpty())
            assertTrue(world.apple.builds.isEmpty())
            assertNull(world.receipt())
            world.apple.uploadOutcome = "VALID"
            assertNull(
                world.sweep().run(sha).findings.first().operatorAction?.let {
                    it.takeIf { "operation=deliver" in it }
                }
            )
            assertFails { world.job(Operation.Deliver(sha), run = "new-push") }
            // Replaying the retained bytes proves Apple holds the number: it rejects the repeat.
            val refused = assertFails {
                world.job(
                    Operation.Retry(sha, "234.1", "Reconcile unlisted upload"),
                    run = "retry-run",
                )
            }
            assertContains(refused.message!!, "uncertain")
            assertEquals(listOf("234.1", "234.1"), world.apple.uploadedNumbers)
            assertEquals("90189", world.apple.uploads.single().code)
            assertFalse("distribute" in world.apple.calls)
            assertNull(world.receipt())
            val waiting =
                world.beta("waiting-run").start(Operation.Retry(sha, "234.1", "Try again"))
            assertContains(assertIs<StartDecision.Refused>(waiting).reason, "still processing")
            world.apple.reveal()
            val retry = Operation.Retry(sha, "234.1", "Accepted build now visible")
            world.job(retry, run = "resume-run")
            world.job(retry, run = "completed-run")
            assertEquals(listOf("234.1", "234.1"), world.apple.uploadedNumbers)
            assertEquals(listOf(sha), world.builds.verified)
            assertEquals(listOf(sha), world.builds.archived)
            assertEquals("build-234.1", world.apple.builds.single().id)
            assertEquals(1, world.apple.calls.count { it == "distribute" })
            world.assertRetainedBytes(retained)
            world.assertDelivered()
        }

    @Test
    fun `a refused repeat never hides the upload Apple is processing, whatever the order`() =
        temp { output ->
            val processing = AppleUpload("26.09.01", "234.1", "PROCESSING")
            val repeat = AppleUpload("26.09.01", "234.1", "FAILED", "90189", "Redundant")
            val rejected = AppleUpload("26.09.01", "234.1", "FAILED", "90001", "Bad binary")
            for (uploads in listOf(listOf(processing, repeat), listOf(repeat, processing))) {
                val world = World(output)
                world.apple.uploads += uploads + rejected
                val decision = world.beta().start(Operation.Retry(sha, "234.1", "Try again"))
                assertContains((decision as StartDecision.Refused).reason, "still processing")
            }
        }

    @Test
    fun `a delivery decides again after verification before taking a number`() = temp { output ->
        val world = World(output)
        // Another job's upload, which the queue lost track of, looks abandoned at the start...
        world.apple.uploads += AppleUpload("26.09.01", "234.1", "UPLOADING")
        // ...and finishes while this retry verifies.
        world.builds.duringVerify = {
            world.apple.uploads.replaceAll { it.copy(state = "COMPLETE") }
        }
        val failure = assertFails { world.retry(sha) }
        assertContains(failure.message!!, "still processing")
        assertContains(failure.message!!, "nothing was archived")
        assertEquals(1, world.builds.verified.size)
        assertTrue(world.builds.archived.isEmpty())
        assertTrue(world.apple.uploadedNumbers.isEmpty())
    }

    @Test
    fun `retries need the latest build number and a reason`() = temp { output ->
        val world = World(output)
        world.apple.rejection = AppleUpload("", "", "FAILED", "90001", "Bad binary")
        assertFails { world.deliver(sha) }
        val refused = world.beta().start(Operation.Deliver(sha)) as StartDecision.Refused
        assertContains(
            refused.reason,
            WorkflowHints(mapOf("GITHUB_REPOSITORY" to "owner/repo")).retry(sha, "234.1"),
        )
        assertFails { world.beta().start(Operation.Retry(sha, "none", "Stale")) }
        assertFails { world.beta().start(Operation.Retry(sha, "234.1", " ")) }
        assertIs<StartDecision.Deliver>(world.beta().start(Operation.Retry(sha, "234.1", "Fixed")))
    }

    @Test
    fun `an older uncertain upload does not block delivery of a newer tip`() = temp { output ->
        val world = World(output)
        world.source.shas = listOf(sha, "new-tip")
        world.apple.uploadOutcome = "INVISIBLE"
        assertFails { world.deliver(sha) }
        world.apple.uploadOutcome = "VALID"
        world.deliver("new-tip")
        assertNotNull(world.receipt("235.1"))
        assertEquals(2, world.apple.calls.count { it == "upload" })
    }

    @Test
    fun `new builds move to the next marketing version once Apple has one submitted`() =
        temp { output ->
            val world = World(output)
            world.apple.versions +=
                StoreVersion("v1", "26.09.01", "WAITING_FOR_REVIEW", "b", "200.1", null)
            world.deliver(sha)
            assertNotNull(world.tags.tags["deploy/testflight/26.09.02/234.1"])
        }

    @Test
    fun `a repeated upload step never uploads the same number twice`() = temp { output ->
        val world = World(output)
        val run = "77"
        val beta = world.beta(run)
        val operation = Operation.Deliver(sha)
        world.artifacts.retain(run, beta.build(operation, output)!!, output.resolve(sha))
        val retained = world.retainedBytes()
        world.apple.loseResponseAfter("distribute") {
            assertFails { beta.upload(operation, output) }
        }
        assertTrue(
            "build-234.1" in world.apple.testers,
            "Distribution took effect before response loss",
        )
        assertNull(world.receipt())
        // Actions reruns the step in the same run, with this run's retained build.
        val confirmed = assertNotNull(world.beta(run).upload(operation, output))
        assertEquals(MarketingVersion("26.09.01"), confirmed.version)
        assertEquals(BuildNumber("234.1"), confirmed.number)
        assertEquals(listOf(sha), world.builds.verified)
        assertEquals(listOf(sha), world.builds.archived)
        assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
        assertEquals(1, world.apple.calls.count { it == "distribute" })
        world.assertRetainedBytes(retained)
        world.assertDelivered()
        assertNull(world.beta("already-done").upload(operation, output))
    }

    @Test
    fun `an Actions rerun uploads the build its first attempt retained without rebuilding`() =
        temp { output ->
            val world = World(output)
            val run = "88"
            // Termination before upload means Apple has accepted no effect yet.
            world.apple.interrupt = { if (it == "before-upload") throw ProcessInterrupted() }
            assertFailsWith<ProcessInterrupted> { world.job(Operation.Deliver(sha), run = run) }
            world.apple.interrupt = null
            val retained = world.retainedBytes()
            assertEquals(run, world.artifacts.retained.getValue("ios-build-234.1").first)
            assertTrue(world.apple.calls.isEmpty())
            assertTrue(world.apple.uploadedNumbers.isEmpty())
            assertNull(world.receipt())
            world.job(Operation.Rerun(sha), run = run)
            assertEquals(listOf(sha), world.builds.verified)
            assertEquals(listOf(sha), world.builds.archived)
            assertEquals(listOf("234.1"), world.apple.uploadedNumbers)
            world.assertRetainedBytes(retained)
            world.assertDelivered()
        }

    @Test
    fun `a rerun after a rejection builds the next number`() = temp { output ->
        val world = World(output)
        world.apple.rejection = AppleUpload("", "", "FAILED", "90001", "Bad binary")
        assertFails { world.job(Operation.Deliver(sha), run = "88") }
        world.apple.rejection = null
        world.job(Operation.Rerun(sha), run = "88")
        assertEquals(listOf("234.1", "234.2"), world.apple.uploadedNumbers)
        assertNotNull(world.receipt("234.2"))
    }

    @Test
    fun `a rerun is refused once a later run has taken over`() = temp { output ->
        val world = World(output)
        world.apple.rejection = AppleUpload("", "", "FAILED", "90001", "Bad binary")
        assertFails { world.job(Operation.Deliver(sha), run = "88") }
        world.apple.rejection = null
        world.apple.distributionReachesTesters = false
        assertFails { world.job(Operation.Retry(sha, "234.1", "Fixed"), run = "89") }
        val stale = world.beta("88").start(Operation.Rerun(sha))
        assertContains((stale as StartDecision.Refused).reason, "234.2")
        assertEquals(listOf("234.1", "234.2"), world.apple.uploadedNumbers)
    }

    @Test
    fun `a rerun of a retry keeps the retry's authority`() = temp { output ->
        val world = World(output)
        world.apple.rejection = AppleUpload("", "", "FAILED", "90001", "Bad binary")
        assertFails { world.deliver(sha) }
        world.apple.rejection = null
        world.builds.verifyError = CommandError("Simulator did not boot")
        assertFails { world.job(Operation.Retry(sha, "234.1", "Fixed"), run = "90") }
        world.builds.verifyError = null
        // The rerun retained nothing, but the retry it replays still names the latest build.
        world.job(Operation.Rerun(sha, expectedAttempt = "234.1"), run = "90")
        assertNotNull(world.receipt("234.2"))
    }
}
