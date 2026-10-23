package delivery

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class SigningLockTest {
    @Test
    fun `separate processes wait until signing cleanup releases the lock`() = temp { home ->
        checkProcesses(home, crash = false)
    }

    @Test
    fun `process termination releases the signing lock`() = temp { home ->
        checkProcesses(home, crash = true)
    }

    private fun checkProcesses(home: Path, crash: Boolean) {
        val first = start(home, "first")
        var second: Process? = null
        try {
            awaitOutput(home.resolve("first"), "entered")
            second = start(home, "second")
            awaitOutput(home.resolve("second"), "started")
            Thread.sleep(200)
            assertFalse(home.resolve("second").readText().contains("entered"))
            if (crash) first.destroyForcibly() else first.outputStream.close()
            assertTrue(first.waitFor(10, TimeUnit.SECONDS))
            awaitOutput(home.resolve("second"), "entered")
            second.outputStream.close()
            assertTrue(second.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, second.exitValue(), home.resolve("second").readText())
        } finally {
            first.destroyForcibly().waitFor()
            second?.destroyForcibly()?.waitFor()
        }
    }

    private fun start(home: Path, name: String): Process {
        val classpath =
            listOf(SigningLockProbe::class.java, Signing::class.java, Unit::class.java)
                .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
                .distinct()
                .joinToString(java.io.File.pathSeparator)
        return ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                classpath,
                SigningLockProbe::class.java.name,
                home.toString(),
            )
            .redirectErrorStream(true)
            .redirectOutput(home.resolve(name).toFile())
            .start()
    }

    private fun awaitOutput(path: Path, text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (path.exists() && text in path.readText()) return
            Thread.sleep(10)
        }
        fail("Expected $text: ${if (path.exists()) path.readText() else "no output"}")
    }

    @Test
    fun `threads serialize and release after an exception`() = temp { home ->
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        try {
            val first = executor.submit {
                assertFailsWith<IllegalStateException> {
                    withSigningLock(home) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        error("archive failed")
                    }
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second = executor.submit {
                waiting.countDown()
                withSigningLock(home) { secondEntered.countDown() }
            }
            assertTrue(waiting.await(10, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
            assertEquals(0, secondEntered.count)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

object SigningLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println("started")
        withSigningLock(Path.of(args.single())) {
            println("entered")
            System.`in`.read()
        }
    }
}
