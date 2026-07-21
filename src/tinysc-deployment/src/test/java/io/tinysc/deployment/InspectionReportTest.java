package io.tinysc.deployment;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InspectionReportTest {
    @Test
    void usesLegacyDescriptorToResolveMixedBundledAdapters() {
        InspectionReport report = report("3.0", InspectionReport.ServletNamespace.MIXED);

        assertEquals("tinysc 1.x (mixed dependencies; verify)",
                report.recommendedRuntime());
    }

    @Test
    void usesModernDescriptorToResolveMixedBundledAdapters() {
        InspectionReport report = report("6.1", InspectionReport.ServletNamespace.MIXED);

        assertEquals("tinysc 2.x (mixed dependencies; verify)",
                report.recommendedRuntime());
    }

    @Test
    void requiresManualReviewWhenMixedWarHasNoDescriptor() {
        InspectionReport report = report("absent", InspectionReport.ServletNamespace.MIXED);

        assertEquals("manual review required (mixed namespace)",
                report.recommendedRuntime());
    }

    private static InspectionReport report(
            String version, InspectionReport.ServletNamespace namespace) {
        return new InspectionReport(Paths.get("app.war"), 1L, "sha", version,
                namespace, 1, 52, 52, false, Collections.<String>emptyList());
    }
}
