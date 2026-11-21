package delivery

import kotlin.test.*
import org.junit.Test

class ReportingChecksTest {
    @Test
    fun `split operation without boundary reporting is rejected`() {
        val document =
            workflowDocument(
                """
                name: Continuous Integration
                on: { push: { branches: [trunk] } }
                jobs:
                  ci:
                    steps:
                      - uses: owner/tool/ci@${"a".repeat(40)}
                """
                    .trimIndent()
            )
        assertFails {
            validateSplitWorkflow(document, yamlMapping(yamlMapping(document["jobs"])["ci"]), "ci")
        }
    }

    @Test
    fun `reporter is independent of failed setup and serialized with minimum permissions`() {
        val valid =
            """
            jobs:
              operation: { name: App CI, steps: [] }
              report:
                if: always()
                needs: operation
                runs-on: ubuntu-latest
                permissions: { contents: read, actions: read, checks: read, issues: write }
                concurrency: { group: mobile-delivery-reporting, queue: max, cancel-in-progress: false }
                steps:
                  - uses: owner/tool/report-failure@${"a".repeat(40)}
                    with: { role: ci, operation-job: App CI, integration-branch: trunk }
            """
                .trimIndent()
        validateReportingJob(workflowDocument(valid), "report")
        for (changed in
            listOf(
                valid.replace("queue: max", "queue: single"),
                valid.replace("cancel-in-progress: false", "cancel-in-progress: true"),
                valid.replace("if: always()", "if: failure()"),
                valid.replace("actions: read", "actions: write"),
                valid.replace("checks: read, ", ""),
                valid.replace("checks: read", "checks: write"),
                valid.replace("needs: operation", "needs: missing"),
                valid.replace(
                    "steps:\n      - uses:",
                    "steps:\n      - run: ./gradlew installDist\n      - uses:",
                ),
            )) assertFails { validateReportingJob(workflowDocument(changed), "report") }
    }
}
