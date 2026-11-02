package delivery

import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class RedStreakSuspectsTest {
    @Test
    fun `range uses latest receipt first parents and proven superseded runs`() = temp { root ->
        val process = ProcessCommands()
        fun git(vararg args: String) = process.run(listOf("git", *args), cwd = root).trim()
        git("init", "-b", "trunk")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.invalid")
        fun commit(subject: String): String {
            git("commit", "--allow-empty", "-m", subject)
            return git("rev-parse", "HEAD")
        }
        val baseline = commit("baseline")
        val delivered = commit("delivered")
        val skippedOne = commit("skipped one")
        val skippedTwo = commit("skipped two")
        // A merged side branch must not appear in the first-parent suspect list.
        git("checkout", "-b", "side")
        commit("side change")
        git("checkout", "trunk")
        git("merge", "--no-ff", "side", "-m", "first red")
        val firstRed = git("rev-parse", "HEAD")
        val source = GitSource(root, config, process, "trunk")
        val tags =
            object : ReceiptTags {
                override fun names(prefix: String) =
                    setOf("deploy/testflight/26.09.01/1.1", "deploy/testflight/26.09.01/2.1")

                override fun commit(tag: String) = if (tag.endsWith("/2.1")) delivered else baseline

                override fun write(tag: String, sha: String, receipt: Receipt) = error("read only")
            }
        val commands = RecordingCommands()
        var testedRerun = false
        commands.respond = { call ->
            val path = call.args[2]
            val body =
                when {
                    "actions/runs?" in path -> {
                        val sha = path.substringAfter("head_sha=").substringBefore('&')
                        """{"workflow_runs":[{"id":10,"head_sha":"$sha","conclusion":"${if (testedRerun) "failure" else "cancelled"}","path":".github/workflows/testflight-delivery.yml"}]}"""
                    }
                    "actions/runs/10/jobs" in path ->
                        """{"jobs":[{"steps":[{"name":"Select the delivery operation","conclusion":"cancelled"},{"name":"Verify the app","conclusion":"skipped"}]}]}"""
                    else -> error("Unexpected API $path")
                }
            CommandResult(0, body, "")
        }
        val github =
            GitHub("owner/repo", commands, mapOf("DELIVERY_WORKFLOW_REPOSITORY" to "owner/tool"))
        for (sha in listOf(baseline, delivered, skippedOne, skippedTwo)) {
            github.actionRoleCache["testflight-delivery.yml@$sha"] = setOf("testflight")
        }
        val range = redStreakSuspects(firstRed, baseline, source, tags, github)
        assertEquals(delivered, range.base)
        assertFalse(range.fromBaseline)
        assertEquals(listOf(skippedOne, skippedTwo, firstRed), range.commits.map { it.sha })
        assertEquals(listOf(true, true, false), range.commits.map { it.untested })
        assertEquals(
            listOf("skipped one", "skipped two", "first red"),
            range.commits.map { it.subject },
        )
        testedRerun = true
        assertTrue(
            redStreakSuspects(firstRed, baseline, source, tags, github).commits.none { it.untested }
        )
        val noTags =
            object : ReceiptTags by tags {
                override fun names(prefix: String) = emptySet<String>()
            }
        val fallback = redStreakSuspects(firstRed, baseline, source, noTags, github)
        assertEquals(baseline, fallback.base)
        assertTrue(fallback.fromBaseline)
        assertContains(
            renderRedStreak(
                listOf(RedStreakCommit(firstRed, "red", "run")),
                emptyList(),
                suspects = fallback,
                repo = "owner/repo",
            ),
            "Range: baseline $baseline..$firstRed",
        )
    }
}
