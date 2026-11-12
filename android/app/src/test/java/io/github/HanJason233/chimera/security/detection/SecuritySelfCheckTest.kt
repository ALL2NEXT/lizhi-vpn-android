package io.github.HanJason233.chimera.security.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecuritySelfCheckTest {
    @Test
    fun componentFailureNeverReportsEnabledChecksAsPassed() {
        val report = report(setOf("native_library_unavailable"))
        assertTrue(report.items.all { it.status == SecurityCheckStatus.UNAVAILABLE })
        assertEquals(0, report.completedCount)
        assertEquals("部分检查未能完成", report.title)
    }

    @Test
    fun scanErrorCannotLookLikeAnEmptySuccessfulScan() {
        val report = report(setOf("native_scan_error"))
        assertFalse(report.items.any { it.status == SecurityCheckStatus.PASS })
        assertEquals(0, report.completedCount)
    }

    @Test
    fun disabledDetectionDoesNotClaimEnvironmentIsSafe() {
        val options = SecurityCheckOptions(false, false, false, false, false, false, false, false, false)
        val report = report(emptySet(), options)
        assertEquals("环境检测未启用", report.title)
        assertEquals(0, report.completedCount)
        assertTrue(report.environmentItems.all { it.status == SecurityCheckStatus.DISABLED })
    }

    @Test
    fun incompleteProxyInspectionIsNotPassed() {
        assertEquals(SecurityCheckStatus.UNAVAILABLE,
            report(setOf("capture_detection_unavailable")).item("proxy").status)
    }

    @Test
    fun openPortAloneDoesNotClaimFridaInjection() {
        val report = report(setOf("frida_listener"))
        assertEquals(SecurityCheckStatus.NOTICE, report.item("frida").status)
        assertEquals(0, report.riskCount)
        assertTrue(report.item("frida").details.single().contains("不能确认"))
    }

    @Test
    fun injectionEvidenceIsDistinctFromUnreadableProcessInformation() {
        val risk = report(setOf("frida_maps")).item("frida")
        val unavailable = report(setOf("process_maps_unavailable")).item("frida")
        assertEquals(SecurityCheckStatus.RISK, risk.status)
        assertEquals(SecurityCheckStatus.UNAVAILABLE, unavailable.status)
    }

    @Test
    fun installedSoftwareAndDeveloperSettingsAreAdvisorySignals() {
        val report = report(setOf(
            "blacklisted_package:com.example.capture", "adb_enabled", "developer_options_enabled"
        ))
        assertEquals(0, report.riskCount)
        assertEquals(3, report.noticeCount)
        assertEquals(listOf("com.example.capture"), report.item("blacklist").details)
        assertTrue(report.item("blacklist").summary.contains("不代表"))
    }

    @Test
    fun screenshotMonitoringIsNotCountedAsAnEnvironmentPass() {
        val active = report(emptySet())
        assertEquals(SecurityCheckStatus.MONITORING, active.item("screenshot").status)
        assertEquals(8, active.completedCount)
        val unsupported = buildSecuritySelfCheckReport(
            SecurityInspection(emptySet(), 123, 10),
            SecurityCheckOptions(),
            ScreenshotCaptureStatus.UNSUPPORTED
        )
        assertEquals(SecurityCheckStatus.UNSUPPORTED, unsupported.item("screenshot").status)
        assertEquals(0, unsupported.unavailableCount)
    }

    @Test
    fun unknownNativeSignalCannotBeSilentlyShownAsClean() {
        val report = report(setOf("future_native_signal"))
        assertEquals(SecurityCheckStatus.NOTICE, report.item("other").status)
        assertTrue(report.noticeCount > 0)
    }

    @Test
    fun eventDescriptionsExplainThatTheyAreHistorical() {
        assertTrue(securityEventDescription("tls_validation_failed")!!.second.contains("不代表当前"))
        assertTrue(securityEventDescription("screenshot_capture")!!.second.contains("本次运行"))
    }

    private fun report(codes: Set<String>, options: SecurityCheckOptions = SecurityCheckOptions()) =
        buildSecuritySelfCheckReport(
            SecurityInspection(codes, 123, 10), options, ScreenshotCaptureStatus.ACTIVE
        )

    private fun SecuritySelfCheckReport.item(id: String) = items.single { it.id == id }
}
