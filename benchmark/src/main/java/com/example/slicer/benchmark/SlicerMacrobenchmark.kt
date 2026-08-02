package com.example.slicer.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class SlicerMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithFourHundredResults() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                StartupTimingMetric(),
                FrameTimingMetric(),
                MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
                TraceSectionMetric("Slicer UI state", TraceSectionMetric.Mode.Average)
            ),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                seedCompletedResults(400)
                pressHome()
            }
        ) {
            startActivityAndWait()
            assertResultsVisible()
        }
    }

    @Test
    fun scrollOneThousandResults() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                FrameTimingMetric(),
                MemoryUsageMetric(MemoryUsageMetric.Mode.Max)
            ),
            compilationMode = CompilationMode.None(),
            iterations = 5,
            setupBlock = {
                seedCompletedResults(1_000)
                startActivityAndWait()
                assertResultsVisible()
            }
        ) {
            val centerX = device.displayWidth / 2
            val startY = device.displayHeight * 3 / 4
            val endY = device.displayHeight / 4
            var completedSwipes = 0
            repeat(8) {
                if (device.swipe(centerX, startY, centerX, endY, 12)) completedSwipes++
            }
            device.waitForIdle()
            assertTrue("The result list did not accept a scroll gesture.", completedSwipes > 0)
            assertTrue(
                "The result list did not move away from its header.",
                device.wait(Until.gone(By.text("Last run files")), UI_TIMEOUT_MILLIS)
            )
        }
    }

    private fun MacrobenchmarkScope.seedCompletedResults(count: Int) {
        val output = device.executeShellCommand(
            "am broadcast -W --receiver-foreground " +
                "-a $SEED_ACTION -n $SEED_COMPONENT --ei $RESULT_COUNT_EXTRA $count"
        )
        assertTrue("Benchmark seed failed: $output", output.contains("result=-1"))
        killProcess()
    }

    private fun MacrobenchmarkScope.assertResultsVisible() {
        assertTrue(
            "Results screen did not become visible.",
            device.wait(Until.hasObject(By.text("Last run files")), UI_TIMEOUT_MILLIS)
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.slicer.benchmark"
        const val SEED_ACTION = "com.example.slicer.SEED_BENCHMARK_RESULTS"
        const val SEED_COMPONENT =
            "com.example.slicer.benchmark/com.example.slicer.benchmark.BenchmarkDataReceiver"
        const val RESULT_COUNT_EXTRA = "result_count"
        const val UI_TIMEOUT_MILLIS = 10_000L
    }
}
