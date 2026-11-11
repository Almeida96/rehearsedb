package io.github.almeida96.rehearsedb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlTest {
    @Test void showsTheReasonForAFailedRollbackAheadOfTheStageDetails() throws Exception {
        var report = Fileset.JSON.readTree("""
                {"status":"FAIL","stages":{"forward":{"status":"PASS"},"rollback":{
                 "status":"FAIL","matchesBaseline":false,"changes":[{"kind":"table","name":"items<script>",
                   "change":"modified","before":{"rows":3,"schemaSha256":"same","dataSha256":"abc"},
                   "after":{"rows":3,"schemaSha256":"same","dataSha256":"def"}}]}}}
                """);
        String html = Html.render(report);
        assertTrue(html.contains("Row contents differ after rollback"));
        assertTrue(html.contains("<code>items&lt;script&gt;</code> table has 3 rows in both snapshots"));
        assertTrue(html.indexOf("Row fingerprint") < html.indexOf("Baseline and migration fingerprints"));
        assertFalse(html.contains("items<script>"));
    }

    @Test void reportsAFailedPolicyWithoutBlamingAnExpectedForwardDifference() throws Exception {
        var report = Fileset.JSON.readTree("""
                {"status":"FAIL","stages":{"forward":{"status":"FAIL","changes":[{"name":"added","change":"added"}],
                 "checks":[{"ok":false,"type":"unique","table":"orders","column":"number"}]},
                 "rollback":{"status":"NOT_RUN"}}}
                """);
        String html = Html.render(report);
        assertTrue(html.contains("<h1>Forward migration failed</h1>"));
        assertTrue(html.contains("failed on <code>orders.number</code>"));
        assertFalse(html.contains("Row contents differ after rollback"));
    }
}
