package com.east.app.mailread;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/** Restricts cached message HTML before the API makes it available to a client. */
final class MailHtmlSanitizer {

    private static final Safelist ALLOWED = Safelist.none()
            .addTags("a", "b", "blockquote", "br", "caption", "code", "dd", "div", "dl", "dt",
                    "em", "h1", "h2", "h3", "h4", "hr", "i", "li", "ol", "p", "pre",
                    "small", "span", "strong", "sub", "sup", "table", "tbody", "td", "th",
                    "thead", "tr", "u", "ul")
            .addAttributes("a", "href", "title", "target", "rel")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addProtocols("a", "href", "http", "https", "mailto");

    private MailHtmlSanitizer() { }

    static String sanitize(String html) {
        if (html == null || html.isEmpty()) return null;
        Document source = Jsoup.parseBodyFragment(html);
        Document cleaned = new Cleaner(ALLOWED).clean(source);
        cleaned.select("a[href]").forEach(link -> {
            link.attr("target", "_blank");
            link.attr("rel", "noopener noreferrer");
        });
        return cleaned.body().html();
    }

    static String toText(String html) {
        if (html == null || html.isEmpty()) return null;
        return Jsoup.parseBodyFragment(html).text();
    }
}
