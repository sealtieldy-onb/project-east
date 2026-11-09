package com.east.app.drafts;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
final class DraftHtmlSanitizer {

    private static final Safelist ALLOWED = Safelist.none()
            .addTags("a", "b", "br", "em", "i", "li", "ol", "p", "s", "strong", "u", "ul")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https", "mailto");

    String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Document source = Jsoup.parseBodyFragment(html);
        Document cleaned = new Cleaner(ALLOWED).clean(source);
        return cleaned.body().html();
    }

    boolean isSafe(String html) {
        if (html == null || html.isEmpty()) {
            return true;
        }
        return new Cleaner(ALLOWED).isValid(Jsoup.parseBodyFragment(html));
    }
}
