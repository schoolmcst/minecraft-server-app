// Turns mod descriptions (Modrinth: Markdown with some HTML, CurseForge: HTML) into the plain HTML that Swing can show.
// Pictures are taken out of the text (they're shown in the gallery instead), and anything fancy is dropped.

import java.util.*;
import java.util.regex.*;

final class Markdown {
    private Markdown() { }

    private static final Set<String> KEEP = Set.of("p", "br", "b", "strong", "i", "em", "u", "a", "ul", "ol", "li", "h1", "h2", "h3",
            "h4", "h5", "h6", "pre", "code", "blockquote", "hr", "table", "tr", "td", "th", "center", "s", "strike", "sub", "sup", "tt");
    private static final Pattern IMG_MD = Pattern.compile("!\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");
    private static final Pattern IMG_TAG = Pattern.compile("(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']?([^\"'\\s>]+)[^>]*>");
    private static final Pattern BADGE = Pattern.compile("(?i)shields\\.io|badge|/button|buttons?/|discord|ko-?fi|patreon|paypal|bisect|"
            + "kinetic|curseforge\\.com/.*/(logo|cf)|modrinth\\.com/.*icon|\\.svg(\\?|$)|github\\.com/[^/]+/[^/]+/workflows|"
            + "cdn\\.modrinth\\.com/data/cached_images/[0-9a-f]+\\.png$");

    /** Picture links found in a description, skipping badges and buttons. */
    static List<String> images(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        Matcher m = IMG_MD.matcher(text);
        while (m.find()) out.add(m.group(2));
        m = IMG_TAG.matcher(text);
        while (m.find()) out.add(m.group(1).replace("&amp;", "&"));
        List<String> keep = new ArrayList<>();
        for (String u : out) if (u.startsWith("https://") && !BADGE.matcher(u).find()) keep.add(u);
        return keep;
    }

    // ------------------------------------------------------------------ Markdown

    static String toHtml(String md) {
        StringBuilder out = new StringBuilder();
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder para = new StringBuilder();
        String list = null; // "ul" / "ol"
        boolean code = false, quote = false;
        for (String raw : lines) {
            String line = raw.replace("\t", "    ");
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                flush(out, para);
                list = closeList(out, list);
                out.append(code ? "</pre>" : "<pre>");
                code = !code;
                continue;
            }
            if (code) { out.append(esc(line)).append("\n"); continue; }
            if (t.isEmpty()) { flush(out, para); list = closeList(out, list); if (quote) { out.append("</blockquote>"); quote = false; } continue; }
            Matcher h = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*$").matcher(t);
            if (h.matches()) {
                flush(out, para);
                list = closeList(out, list);
                int lvl = Math.min(4, h.group(1).length() + 1);
                out.append("<h").append(lvl).append(">").append(inline(h.group(2))).append("</h").append(lvl).append(">");
                continue;
            }
            if (t.matches("^([-*_])(\\s*\\1){2,}$")) { flush(out, para); list = closeList(out, list); out.append("<hr>"); continue; }
            if (t.startsWith("|") && t.endsWith("|")) {
                if (t.matches("^\\|[\\s:|-]+\\|$")) continue;
                flush(out, para);
                list = closeList(out, list);
                out.append("<p>");
                List<String> cells = new ArrayList<>();
                for (String c : t.substring(1, t.length() - 1).split("\\|")) if (!c.isBlank()) cells.add(inline(c.trim()));
                out.append(String.join(" &nbsp;·&nbsp; ", cells)).append("</p>");
                continue;
            }
            Matcher li = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$").matcher(line);
            if (li.matches()) {
                flush(out, para);
                String kind = Character.isDigit(li.group(2).charAt(0)) ? "ol" : "ul";
                if (!kind.equals(list)) { list = closeList(out, list); out.append("<").append(kind).append(">"); list = kind; }
                out.append("<li>").append(inline(li.group(3))).append("</li>");
                continue;
            }
            if (t.startsWith(">")) {
                flush(out, para);
                if (!quote) { out.append("<blockquote>"); quote = true; }
                out.append(inline(t.replaceFirst("^>+\\s?", ""))).append(" ");
                continue;
            }
            if (list != null && line.startsWith("  ")) { out.append("<li>").append(inline(t)).append("</li>"); continue; }
            list = closeList(out, list);
            if (para.length() > 0) para.append(line.endsWith("  ") ? "<br>" : " ");
            para.append(inline(t));
        }
        flush(out, para);
        closeList(out, list);
        if (code) out.append("</pre>");
        if (quote) out.append("</blockquote>");
        return cleanHtml(out.toString());
    }

    private static void flush(StringBuilder out, StringBuilder para) {
        if (para.length() == 0) return;
        out.append("<p>").append(para).append("</p>");
        para.setLength(0);
    }

    private static String closeList(StringBuilder out, String list) {
        if (list != null) out.append("</").append(list).append(">");
        return null;
    }

    private static String inline(String s) {
        s = IMG_MD.matcher(s).replaceAll("");
        // keep raw HTML tags; escape stray < and &
        s = s.replaceAll("&(?![a-zA-Z]+;|#\\d+;|#x[0-9a-fA-F]+;)", "&amp;").replaceAll("<(?![a-zA-Z/!])", "&lt;");
        List<String> codes = new ArrayList<>();
        Matcher c = Pattern.compile("`([^`]+)`").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (c.find()) { codes.add(c.group(1).replace("<", "&lt;")); c.appendReplacement(sb, "\u0000" + (codes.size() - 1) + "\u0000"); }
        c.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)", "<a href=\"$2\">$1</a>");
        s = s.replaceAll("<(https?://[^>\\s]+)>", "<a href=\"$1\">$1</a>");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>").replaceAll("__(.+?)__", "<b>$1</b>");
        s = s.replaceAll("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)", "<i>$1</i>");
        s = s.replaceAll("(?<![\\w])_(?!\\s)(.+?)(?<!\\s)_(?![\\w])", "<i>$1</i>");
        s = s.replaceAll("~~(.+?)~~", "<s>$1</s>");
        for (int i = 0; i < codes.size(); i++) s = s.replace("\u0000" + i + "\u0000", "<code>" + Matcher.quoteReplacement(codes.get(i)) + "</code>");
        return s;
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    // ------------------------------------------------------------------ HTML

    /** Keeps simple formatting and links; removes pictures, scripts, styles and attributes. */
    static String cleanHtml(String html) {
        String s = html.replaceAll("(?is)<(script|style|iframe|video|object|noscript|svg)\\b.*?</\\1\\s*>", "");
        s = s.replaceAll("(?is)<!--.*?-->", "");
        s = IMG_TAG.matcher(s).replaceAll("");
        s = s.replaceAll("(?i)<img\\b[^>]*>", "");
        Matcher m = Pattern.compile("(?is)<(/?)([a-zA-Z][a-zA-Z0-9]*)\\b([^>]*)>").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String close = m.group(1), tag = m.group(2).toLowerCase(Locale.ROOT), attrs = m.group(3);
            String rep;
            if (tag.equals("div") || tag.equals("details") || tag.equals("summary") || tag.equals("figure")) rep = close.isEmpty() && tag.equals("summary") ? "<p><b>" : close.isEmpty() ? "" : tag.equals("summary") ? "</b></p>" : "<br>";
            else if (!KEEP.contains(tag)) rep = "";
            else if (tag.equals("a") && close.isEmpty()) {
                Matcher href = Pattern.compile("(?i)href\\s*=\\s*[\"']?([^\"'\\s>]+)").matcher(attrs);
                rep = href.find() && href.group(1).matches("(?i)https?://.*") ? "<a href=\"" + href.group(1) + "\">" : "<a>";
            } else rep = "<" + close + tag + (tag.equals("br") || tag.equals("hr") ? "" : "") + ">";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("(?is)<a[^>]*>\\s*</a>", "");
        s = s.replaceAll("(?is)<(p|center|li|h\\d|b|strong)>\\s*(<br>\\s*)*</\\1>", "");
        s = s.replaceAll("(?is)(<br>\\s*){3,}", "<br><br>");
        return s.trim();
    }
}
