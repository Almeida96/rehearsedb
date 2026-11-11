package io.github.almeida96.rehearsedb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

final class Html {
    private Html() {}

    static String render(JsonNode report) {
        StringBuilder out = new StringBuilder("""
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="light"><title>SQLite migration rehearsal | RehearseDB</title>
            <style>
            :root{color-scheme:light;--ink:#18332d;--muted:#4b615a;--rule:#cbd3c9;--paper:#f6f6f0;--white:#fefefb}
            *{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.5 "Inter",system-ui,sans-serif}
            main{max-width:68rem;margin:auto;padding:0 clamp(1rem,5vw,3.5rem) 3rem}
            .mast{border-top:4px solid var(--ink);border-bottom:1px solid var(--rule);padding:1rem 0;display:flex;align-items:center;gap:1rem;flex-wrap:wrap}
            .mast strong{letter-spacing:-.055em;font-size:1.45rem}.mast span{color:var(--muted);font-size:.88rem}
            .mast a{margin-left:auto}a{color:#1d6048;text-underline-offset:.22em;font-weight:600}
            header{padding:clamp(1.5rem,4vw,2.8rem) 0 1.5rem}header p{margin:.6rem 0 0;font-size:1.06rem;max-width:68ch}
            h1,h2,h3,p{overflow-wrap:anywhere}h1{font:600 clamp(2.35rem,5.2vw,3.8rem)/1.05 "STIX Two Text",Georgia,serif;letter-spacing:-.053em;margin:.8rem 0}
            h2{font-size:1.35rem;letter-spacing:-.025em;margin:0}h3{font-size:1rem;margin:1.2rem 0 .35rem}
            section{margin:1.25rem 0;padding:1.3rem clamp(1rem,2.6vw,1.65rem);background:var(--white);border:1px solid var(--rule);border-radius:.45rem}
            .section-title,.context{display:flex;align-items:center;gap:.7rem;flex-wrap:wrap}.context code{color:var(--muted)}
            .badge{display:inline-block;padding:.15rem .58rem;background:#e4e9e3;color:#344b44;border-radius:.25rem;font:700 .72rem/1.6 system-ui;letter-spacing:.06em}
            .fail{color:#872b1d;background:#f7ded7}.pass{color:#115139;background:#dceee1}code,pre,.hash{font:.88rem/1.55 "IBM Plex Mono",Consolas,monospace;overflow-wrap:anywhere;white-space:pre-wrap}
            header code{font-size:1em}.focus{margin:0 0 1.4rem;padding-top:1.15rem}.focus h2{font-size:1.12rem}.focus h2 code{font-size:1em}
            table{width:100%;border-collapse:collapse;table-layout:fixed;margin-top:.9rem;font-size:.92rem}
            th,td{padding:.55rem .45rem;text-align:left;border-top:1px solid var(--rule);overflow-wrap:anywhere}
            thead th{color:var(--muted);font-size:.78rem}tbody th{font-weight:550}.changed{color:#8d3020;font-weight:700}
            summary{cursor:pointer;font-weight:600}details{border-top:1px solid var(--rule);padding:.65rem 0}ul{padding-left:1.3rem}
            small{color:var(--muted)}pre{padding:.65rem;background:#edf1eb;margin:.45rem 0}a:focus-visible,summary:focus-visible{outline:2px solid #1b674f;outline-offset:3px}
            @media(max-width:35rem){.mast{gap:.45rem .8rem}.mast span{display:none}th,td{padding:.48rem .2rem}.hash{font-size:.74rem}}
            </style></head><body><main><div class="mast"><strong>RehearseDB</strong><span>SQLite migration rehearsal</span><a href="report.json">JSON evidence ↗</a></div>
            """);
        verdict(out, report);
        JsonNode baseline = report.path("baseline");
        out.append("<details><summary>Baseline and migration fingerprints</summary><pre>")
                .append(esc(baseline.toPrettyString())).append("</pre><pre>")
                .append(esc(report.path("inputs").toPrettyString())).append("</pre></details>");
        stage(out, report.path("stages").path("forward"), "Forward migration", "Changes from the baseline after the up script.");
        stage(out, report.path("stages").path("rollback"), "Rollback", "Differences still present after the down script.");
        return out.append("</main></body></html>").toString();
    }

    private static void verdict(StringBuilder out, JsonNode report) {
        String status = report.path("status").asText();
        JsonNode stages = report.path("stages");
        boolean forwardFailed = "FAIL".equals(stages.path("forward").path("status").asText());
        String key = forwardFailed || "FORWARD_ONLY".equals(status) ? "forward" : "rollback";
        JsonNode stage = stages.path(key);
        String label = key.equals("forward") ? "Forward migration" : "Rollback";
        String file = report.path("inputs").path(key).path("file").asText();
        JsonNode changes = stage.path("changes");
        JsonNode first = changes.path(0);
        boolean baselineFailed = report.at("/baseline/health/ok").isBoolean()
                && !report.at("/baseline/health/ok").booleanValue();
        boolean diverged = stage.path("matchesBaseline").isBoolean() && !stage.path("matchesBaseline").booleanValue();
        boolean rowMismatch = changes.size() == 1 && first.path("before").has("dataSha256")
                && first.path("after").has("dataSha256") && differs(first, "dataSha256")
                && !differs(first, "schemaSha256");
        out.append("<header><div class=\"context\">").append(badge(status));
        if (!file.isEmpty() && !baselineFailed) out.append("<code>").append(esc(file)).append("</code>");
        out.append("</div>");
        switch (status) {
            case "PASS" -> out.append("<h1>Rollback restored the baseline</h1><p>The captured objects match after the round trip. Both stages passed their configured checks.</p>");
            case "FORWARD_ONLY" -> out.append("<h1>Forward migration passed</h1><p>Forward checks passed. Supply a down migration to rehearse the rollback.</p>");
            default -> {
                if (baselineFailed) {
                    out.append("<h1>Baseline failed its integrity checks</h1><p>")
                            .append(healthSummary(report.at("/baseline/health"))).append("</p>");
                } else if (stage.has("error")) {
                    out.append("<h1>").append(label).append(" failed</h1><p>").append(esc(stage.path("error").asText())).append("</p>");
                } else if (diverged && !changes.isEmpty()) {
                    out.append("<h1>").append(rowMismatch ? "Row contents differ after rollback" : "Rollback differs from the baseline").append("</h1><p>");
                    if (rowMismatch && first.path("before").has("rows") && first.path("after").has("rows") && !differs(first, "rows")) {
                        long rows = first.at("/after/rows").asLong();
                        out.append("The <code>").append(esc(first.path("name").asText())).append("</code> table has ")
                                .append(rows).append(rows == 1 ? " row" : " rows").append(" in both snapshots. Its row fingerprint changed.");
                    } else {
                        out.append("The down migration left differences in ");
                        int visible = Math.min(3, changes.size());
                        for (int i = 0; i < visible; i++) {
                            if (i > 0) out.append(", ");
                            out.append("<code>").append(esc(changes.get(i).path("name").asText())).append("</code>");
                        }
                        if (changes.size() > visible) out.append(" and ").append(changes.size() - visible).append(" more");
                        out.append('.');
                    }
                    out.append("</p>");
                } else {
                    out.append("<h1>").append(label).append(" failed</h1>");
                    if (stage.has("health") && !stage.at("/health/ok").asBoolean()) {
                        out.append("<p>").append(healthSummary(stage.path("health"))).append("</p>");
                    }
                }
                for (JsonNode check : stage.path("checks")) {
                    if (check.path("ok").asBoolean()) continue;
                    out.append("<p>Check <code>").append(esc(check.path("type").asText())).append("</code> failed on <code>")
                            .append(esc(check.path("table").asText()));
                    if (check.has("column")) out.append('.').append(esc(check.path("column").asText()));
                    out.append("</code>.</p>");
                }
            }
        }
        out.append("</header>");
        if (diverged && first.path("before").has("dataSha256") && first.path("after").has("dataSha256"))
            tableComparison(out, first);
    }

    private static String healthSummary(JsonNode health) {
        return "SQLite reported " + health.path("integrityErrors").asLong() + " integrity errors and "
                + health.path("foreignKeyViolations").asLong() + " foreign-key violations.";
    }

    private static boolean differs(JsonNode diff, String field) {
        return !diff.path("before").path(field).equals(diff.path("after").path(field));
    }

    private static void tableComparison(StringBuilder out, JsonNode diff) {
        out.append("<section class=\"focus\" aria-labelledby=\"comparison\"><h2 id=\"comparison\">Table comparison · <code>")
                .append(esc(diff.path("name").asText())).append("</code></h2><table aria-labelledby=\"comparison\">")
                .append("<thead><tr><th scope=\"col\">Measure</th><th scope=\"col\">Baseline</th><th scope=\"col\">After rollback</th></tr></thead><tbody>");
        for (String field : List.of("rows", "schemaSha256", "dataSha256")) {
            JsonNode before = diff.path("before").path(field), after = diff.path("after").path(field);
            if (before.isMissingNode() || after.isMissingNode()) continue;
            String label = switch (field) { case "rows" -> "Row count"; case "schemaSha256" -> "Definition fingerprint"; default -> "Row fingerprint"; };
            out.append("<tr><th scope=\"row\">").append(label).append("</th><td>").append(cell(before)).append("</td><td");
            if (differs(diff, field)) out.append(" class=\"changed\"");
            out.append('>').append(cell(after)).append("</td></tr>");
        }
        out.append("</tbody></table></section>");
    }

    private static String cell(JsonNode value) {
        String text = value.asText();
        if (value.isTextual() && text.length() == 64) {
            return "<abbr class=\"hash\" title=\"" + esc(text) + "\">" + esc(text.substring(0, 12)) + "…</abbr>";
        }
        return esc(text);
    }

    private static void stage(StringBuilder out, JsonNode stage, String name, String caption) {
        out.append("<section><div class=\"section-title\"><h2>").append(name).append("</h2>")
                .append(badge(stage.path("status").asText("NOT_RUN"))).append("</div>");
        for (String key : List.of("error", "reason")) {
            if (stage.has(key)) out.append("<p>").append(esc(stage.get(key).asText())).append("</p>");
        }
        if (stage.has("health")) {
            JsonNode h = stage.get("health");
            out.append("<p>Integrity errors <strong>").append(h.path("integrityErrors").asLong())
                    .append("</strong> · Foreign-key violations <strong>").append(h.path("foreignKeyViolations").asLong()).append("</strong></p>");
        }
        if (stage.path("checks").size() > 0) {
            out.append("<h3>Policy checks</h3><ul>");
            for (JsonNode check : stage.get("checks")) {
                out.append("<li>").append(badge(check.path("ok").asBoolean() ? "PASS" : "FAIL"))
                        .append(" <code>").append(esc(check.path("type").asText())).append("</code> on ")
                        .append(esc(check.path("table").asText()));
                if (check.has("column")) out.append(" · ").append(esc(check.get("column").asText()));
                if (check.has("violations")) out.append(" · violations ").append(check.path("violations").asLong());
                if (check.has("expectedRows")) out.append(" · rows ").append(check.path("expectedRows").asLong())
                        .append(" → ").append(check.path("actualRows").asLong());
                if (check.has("reason")) out.append(" · ").append(esc(check.path("reason").asText()));
                out.append("</li>");
            }
            out.append("</ul>");
        }
        if (stage.has("changes")) {
            out.append("<h3>Object comparison</h3><p>").append(caption).append("</p>");
            if (stage.get("changes").isEmpty()) out.append("<p>All captured objects match.</p>");
            for (JsonNode diff : stage.get("changes")) {
                JsonNode before = diff.path("before"), after = diff.path("after");
                var dimensions = new ArrayList<String>();
                if (before.isObject() && after.isObject()) {
                    if (!before.path("schemaSha256").equals(after.path("schemaSha256"))) dimensions.add("definition");
                    if (!before.path("dataSha256").equals(after.path("dataSha256"))) dimensions.add("row contents");
                    if (!before.path("value").equals(after.path("value"))) dimensions.add("metadata");
                    if (before.has("rows") && after.has("rows")) dimensions.add("rows " + before.path("rows").asLong() + " → " + after.path("rows").asLong());
                }
                out.append("<details><summary>").append(esc(diff.path("kind").asText())).append(" <code>")
                        .append(esc(diff.path("name").asText())).append("</code> · ").append(esc(diff.path("change").asText()));
                if (!dimensions.isEmpty()) out.append(" · ").append(String.join(", ", dimensions));
                out.append("</summary><small>Baseline</small><pre>").append(esc(before.toPrettyString()))
                        .append("</pre><small>Observed</small><pre>").append(esc(after.toPrettyString())).append("</pre></details>");
            }
        }
        out.append("</section>");
    }

    private static String badge(String status) {
        String tone = switch (status) { case "PASS" -> " pass"; case "FAIL" -> " fail"; default -> ""; };
        return "<span class=\"badge" + tone + "\">" + esc(status) + "</span>";
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
