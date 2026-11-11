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
            *{box-sizing:border-box}body{margin:0;background:#f6f5ef;color:#182e2b;font:16px/1.55 system-ui,sans-serif}
            main{max-width:60rem;margin:auto;padding:clamp(1rem,4vw,3rem)}header{border-top:4px solid #193c35;padding-top:1rem}
            h1{font-size:clamp(2rem,5vw,3.3rem);letter-spacing:-.055em;line-height:1.08;margin:.65rem 0}h2{font-size:1.55rem;letter-spacing:-.03em}
            section{margin:1.8rem 0;padding:1rem clamp(.8rem,3vw,1.65rem);background:#fffef9;border:1px solid #cbd3c8}
            .badge{display:inline-block;padding:.15rem .62rem;border:1px solid #66756c;border-radius:2rem;font:650 .83rem/1.55 system-ui}
            .fail{color:#8e2c16;background:#ffddd0}.pass{color:#144b31;background:#d5eddb}code,pre{font: .86rem/1.6 ui-monospace,monospace;overflow-wrap:anywhere;white-space:pre-wrap}
            summary{cursor:pointer;font-weight:600}details{border-top:1px solid #c6cfc4;padding:.65rem 0}ul{padding-left:1.3rem}
            small{color:#4d5c53}a{color:#135b46}pre{padding:.65rem;background:#f1f3ec;margin:.45rem 0}h3{margin-bottom:.45rem}
            </style></head><body><main><header><strong>RehearseDB</strong>
            <h1>SQLite migration rehearsal</h1>
            """);
        String status = report.path("status").asText();
        out.append(badge(status)).append("<p>").append(switch (status) {
            case "PASS" -> "The rollback restored the captured baseline and all configured checks passed.";
            case "FORWARD_ONLY" -> "Forward checks passed. Supply a down migration to rehearse the rollback.";
            default -> "The rehearsal found a failed check, a migration error or a difference from the baseline.";
        }).append("</p></header>");
        JsonNode baseline = report.path("baseline");
        out.append("<details><summary>Baseline and migration fingerprints</summary><pre>")
                .append(esc(baseline.toPrettyString())).append("</pre><pre>")
                .append(esc(report.path("inputs").toPrettyString())).append("</pre></details>");
        stage(out, report.path("stages").path("forward"), "Forward migration", "Changes from the baseline after the up script.");
        stage(out, report.path("stages").path("rollback"), "Rollback", "Differences still present after the down script.");
        return out.append("<footer><a href=\"report.json\">Open the JSON evidence</a></footer></main></body></html>").toString();
    }

    private static void stage(StringBuilder out, JsonNode stage, String name, String caption) {
        out.append("<section><h2>").append(name).append("</h2>").append(badge(stage.path("status").asText()));
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
