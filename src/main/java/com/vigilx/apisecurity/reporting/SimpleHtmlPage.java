package com.vigilx.apisecurity.reporting;

import java.util.List;

/** Shared minimal HTML page renderer so every report section doesn't repeat the same boilerplate. */
final class SimpleHtmlPage {

    private SimpleHtmlPage() {
    }

    static final String CSS =
            ":root{--bg:#f6f7f9;--card:#fff;--text:#1c1f23;--muted:#606a76;--line:#e3e6ea;"
            + "--pass:#1a7f4b;--fail:#c0392b;--warn:#b8860b}"
            + "@media(prefers-color-scheme:dark){:root{--bg:#15181c;--card:#1d2126;--text:#e8eaed;"
            + "--muted:#9aa4b0;--line:#2c3238;--pass:#4ade80;--fail:#f87171;--warn:#fbbf24}}"
            + "*{box-sizing:border-box}body{margin:0;padding:28px 20px;background:var(--bg);"
            + "color:var(--text);font:14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif}"
            + ".wrap{max-width:1300px;margin:0 auto}"
            + "h1{font-size:20px;margin:0 0 4px}h2{font-size:15px;margin:28px 0 10px}"
            + ".sub{color:var(--muted);margin:0 0 20px;font-size:12px}"
            + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin-bottom:16px}"
            + ".card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px}"
            + ".card .n{font-size:20px;font-weight:700}.card .l{color:var(--muted);font-size:11px;"
            + "text-transform:uppercase;letter-spacing:.4px}"
            + ".n.fail{color:var(--fail)}.n.pass{color:var(--pass)}.n.warn{color:var(--warn)}"
            + "table{width:100%;border-collapse:collapse;background:var(--card);"
            + "border:1px solid var(--line);border-radius:8px;overflow:hidden;font-size:12px}"
            + "th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);vertical-align:top}"
            + "th{background:rgba(128,128,128,.08);font-size:11px;text-transform:uppercase;color:var(--muted)}"
            + ".badge{display:inline-block;padding:1px 7px;border-radius:4px;font-weight:700;"
            + "font-size:11px;color:#fff}.badge.pass{background:var(--pass)}"
            + ".badge.fail{background:var(--fail)}.badge.na{background:var(--muted)}"
            + ".note{color:var(--muted);font-size:12px;margin:8px 0}";

    static String page(String title, String bodyHtml) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(title) + "</title><style>" + CSS + "</style></head>"
                + "<body><div class=\"wrap\"><h1>" + escape(title) + "</h1>" + bodyHtml + "</div></body></html>";
    }

    static String card(String label, Object value, String cssClass) {
        return "<div class=\"card\"><div class=\"n" + (cssClass.isEmpty() ? "" : " " + cssClass) + "\">"
                + value + "</div><div class=\"l\">" + escape(label) + "</div></div>";
    }

    static String badge(String status) {
        String cls = "PASS".equalsIgnoreCase(status) || "TESTED".equalsIgnoreCase(status) ? "pass"
                : "FAIL".equalsIgnoreCase(status) || "FINDING".equalsIgnoreCase(status) ? "fail" : "na";
        return "<span class=\"badge " + cls + "\">" + escape(status) + "</span>";
    }

    static String table(List<String> headers, List<List<String>> rows) {
        StringBuilder html = new StringBuilder("<table><thead><tr>");
        for (String header : headers) {
            html.append("<th>").append(escape(header)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (List<String> row : rows) {
            html.append("<tr>");
            for (String cell : row) {
                html.append("<td>").append(cell).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    static String escape(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
