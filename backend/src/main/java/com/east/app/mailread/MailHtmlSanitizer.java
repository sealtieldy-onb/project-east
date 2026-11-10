package com.east.app.mailread;

import com.east.app.gmail.GmailContentId;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/** Restricts cached message HTML before the API makes it available to a client. */
final class MailHtmlSanitizer {

    private static final Pattern NUMERIC_HOST = Pattern.compile(
            "(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*");
    private static final Pattern CSS_LENGTH = Pattern.compile(
            "(?i)(?:0|[0-9]+(?:\\.[0-9]+)?(?:px|%|em|rem|pt|pc|vh|vw))");
    private static final Pattern CSS_NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern PRESENTATION_SIZE = Pattern.compile("(?i)(?:[0-9]{1,4}(?:px)?|[0-9]{1,3}%)");
    private static final Pattern SMALL_NON_NEGATIVE_INTEGER = Pattern.compile("[0-9]{1,3}");
    private static final Set<String> ALLOWED_STYLE_TAGS = Set.of(
            "a", "blockquote", "caption", "dd", "div", "dl", "dt", "h1", "h2", "h3", "h4",
            "img", "li", "ol", "p", "pre", "small", "span", "table", "tbody", "td", "th",
            "thead", "tr", "ul");

    private static final Safelist ALLOWED = createSafelist();

    private MailHtmlSanitizer() { }

    static String sanitize(String html) {
        return sanitize(html, Map.of());
    }

    /** Keeps CID images local and leaves remote image URLs inert until the user opts in. */
    static String sanitize(String html, Map<String, String> localInlineImageUrls) {
        if (html == null || html.isEmpty()) return null;
        Document source = Jsoup.parseBodyFragment(html);
        prepareImages(source, localInlineImageUrls);
        sanitizeInlineStyles(source);
        sanitizePresentationAttributes(source);
        Document cleaned = new Cleaner(ALLOWED).clean(source);
        cleaned.select("a[href]").forEach(link -> {
            link.attr("target", "_blank");
            link.attr("rel", "noopener noreferrer");
        });
        return cleaned.body().html();
    }

    private static Safelist createSafelist() {
        Safelist safelist = Safelist.none()
                .addTags("a", "b", "blockquote", "br", "caption", "code", "dd", "div", "dl", "dt",
                        "em", "h1", "h2", "h3", "h4", "hr", "i", "img", "li", "ol", "p", "pre",
                        "small", "span", "strong", "sub", "sup", "table", "tbody", "td", "th",
                        "thead", "tr", "u", "ul")
                .addAttributes("a", "href", "title", "target", "rel")
                .addAttributes("img", "src", "alt", "title", "width", "height", "data-east-external-src")
                .addAttributes("table", "width", "height", "align", "border", "cellpadding", "cellspacing")
                .addAttributes("td", "width", "height", "colspan", "rowspan", "align", "valign")
                .addAttributes("th", "width", "height", "colspan", "rowspan", "align", "valign")
                .addAttributes("tr", "align", "valign")
                .addProtocols("a", "href", "http", "https", "mailto");
        for (String tag : ALLOWED_STYLE_TAGS) safelist.addAttributes(tag, "style");
        return safelist;
    }

    /** Keeps only static sizing and alignment styles needed by common table-based email layouts. */
    private static void sanitizeInlineStyles(Document document) {
        for (Element element : document.select("[style]")) {
            String sanitized = safeInlineStyle(element.attr("style"));
            if (sanitized.isEmpty()) element.removeAttr("style");
            else element.attr("style", sanitized);
        }
    }

    private static String safeInlineStyle(String source) {
        Map<String, String> safe = new LinkedHashMap<>();
        for (String declaration : source.split(";")) {
            int separator = declaration.indexOf(':');
            if (separator <= 0) continue;
            String property = declaration.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(separator + 1).trim().toLowerCase(Locale.ROOT);
            if (!isSafeInlineStyle(property, value)) continue;
            safe.put(property, value);
        }
        return safe.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static boolean isSafeInlineStyle(String property, String value) {
        return switch (property) {
            case "width", "min-width", "max-width", "height", "min-height", "max-height" -> isSafeLength(value);
            case "margin", "padding" -> isSafeLengthList(value, 4);
            case "margin-top", "margin-right", "margin-bottom", "margin-left",
                    "padding-top", "padding-right", "padding-bottom", "padding-left",
                    "border-width" -> isSafeLength(value);
            case "border-spacing" -> isSafeLengthList(value, 2);
            case "display" -> Set.of("block", "inline", "inline-block", "table", "inline-table", "table-row",
                    "table-cell", "table-row-group", "table-header-group", "table-footer-group", "list-item")
                    .contains(value);
            case "text-align" -> Set.of("left", "right", "center", "justify", "start", "end").contains(value);
            case "vertical-align" -> Set.of("baseline", "sub", "super", "text-top", "text-bottom", "middle", "top", "bottom")
                    .contains(value) || CSS_LENGTH.matcher(value).matches();
            case "object-fit" -> Set.of("fill", "contain", "cover", "none", "scale-down").contains(value);
            case "border-collapse" -> Set.of("collapse", "separate").contains(value);
            case "border-style" -> Set.of("none", "solid", "dotted", "dashed", "double").contains(value);
            case "line-height" -> value.equals("normal") || isSafeUnitlessLineHeight(value) || isSafeLength(value);
            case "white-space" -> Set.of("normal", "pre", "nowrap", "pre-wrap", "pre-line").contains(value);
            default -> false;
        };
    }

    private static boolean isSafeLength(String value) {
        if (value.equals("auto")) return true;
        if (!CSS_LENGTH.matcher(value).matches()) return false;
        String number = value.replaceFirst("(?i)(?:px|%|em|rem|pt|pc|vh|vw)$", "");
        try {
            double amount = Double.parseDouble(number);
            return Double.isFinite(amount) && amount <= (value.endsWith("%") ? 100 : 2048);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isSafeUnitlessLineHeight(String value) {
        if (!CSS_NUMBER.matcher(value).matches()) return false;
        try {
            double amount = Double.parseDouble(value);
            return Double.isFinite(amount) && amount <= 10;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isSafeLengthList(String value, int maximumItems) {
        String[] lengths = value.trim().split("\\s+");
        if (lengths.length == 0 || lengths.length > maximumItems) return false;
        for (String length : lengths) if (!isSafeLength(length)) return false;
        return true;
    }

    private static void sanitizePresentationAttributes(Document document) {
        for (Element element : document.select("img, table, td, th")) {
            sanitizeSizeAttribute(element, "width");
            sanitizeSizeAttribute(element, "height");
            if (element.hasAttr("align")
                    && !Set.of("left", "right", "center", "justify").contains(element.attr("align").toLowerCase(Locale.ROOT))) {
                element.removeAttr("align");
            }
            if (element.hasAttr("valign")
                    && !Set.of("top", "middle", "bottom", "baseline").contains(element.attr("valign").toLowerCase(Locale.ROOT))) {
                element.removeAttr("valign");
            }
            for (String attribute : Set.of("border", "cellpadding", "cellspacing")) {
                if (!element.hasAttr(attribute)) continue;
                String value = element.attr(attribute);
                if (!SMALL_NON_NEGATIVE_INTEGER.matcher(value).matches() || Integer.parseInt(value) > 128) {
                    element.removeAttr(attribute);
                }
            }
        }
    }

    private static void sanitizeSizeAttribute(Element element, String attribute) {
        if (!element.hasAttr(attribute)) return;
        String value = element.attr(attribute).trim();
        if (!PRESENTATION_SIZE.matcher(value).matches()) {
            element.removeAttr(attribute);
            return;
        }
        String number = value.replaceFirst("(?i)(?:px|%)$", "");
        try {
            double amount = Double.parseDouble(number);
            if (!Double.isFinite(amount) || amount > (value.endsWith("%") ? 100 : 4096)) element.removeAttr(attribute);
        } catch (NumberFormatException exception) {
            element.removeAttr(attribute);
        }
    }

    private static void prepareImages(Document document, Map<String, String> localInlineImageUrls) {
        for (Element image : document.select("img")) {
            String source = image.attr("src").trim();
            image.removeAttr("data-east-external-src").removeAttr("src");
            String contentId = GmailContentId.fromCidUri(source);
            if (contentId != null) {
                String localUrl = localInlineImageUrls.get(contentId);
                if (isLocalAttachmentUrl(localUrl)) image.attr("src", localUrl);
            } else if (isRemoteImageUrl(source)) {
                image.attr("data-east-external-src", source);
            }
            // Never allow another browser fetch mechanism or event hook from untrusted markup.
            image.removeAttr("srcset").removeAttr("onerror").removeAttr("onload");
        }
    }

    private static boolean isLocalAttachmentUrl(String value) {
        return value != null && value.startsWith("/api/v1/threads/") && !value.startsWith("//");
    }

    private static boolean isRemoteImageUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && !isLocalOrPrivateHost(uri.getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isLocalOrPrivateHost(String suppliedHost) {
        String host = suppliedHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()
                || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")
                || host.endsWith(".private") || host.endsWith(".corp")
                || host.endsWith(".intranet") || host.endsWith(".lan") || host.endsWith(".home")
                || host.endsWith(".home.arpa") || host.endsWith(".test")
                || host.endsWith(".invalid") || !host.contains(".")) {
            return true;
        }
        if (host.indexOf(':') >= 0) return isLocalOrPrivateIpv6(host);
        if (NUMERIC_HOST.matcher(host).matches()) return isLocalOrPrivateNumericHost(host);
        return false;
    }

    private static boolean isLocalOrPrivateNumericHost(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return true;
        int[] octets = new int[4];
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].isEmpty() || (parts[index].length() > 1 && parts[index].charAt(0) == '0')
                    || !parts[index].chars().allMatch(Character::isDigit)) return true;
            try {
                octets[index] = Integer.parseInt(parts[index]);
            } catch (NumberFormatException exception) {
                return true;
            }
            if (octets[index] > 255) return true;
        }
        int first = octets[0];
        int second = octets[1];
        int third = octets[2];
        return first == 0 || first == 10 || first == 127
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && (second == 168 || (second == 0 && third == 0)
                        || (second == 0 && third == 2)))
                || (first == 198 && (second == 18 || second == 19
                        || (second == 51 && third == 100)))
                || (first == 203 && second == 0 && third == 113)
                || (first == 100 && second >= 64 && second <= 127)
                || first >= 224;
    }

    private static boolean isLocalOrPrivateIpv6(String host) {
        if (host.indexOf('%') >= 0) return true;
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet6Address)) return true;
            byte[] bytes = address.getAddress();
            boolean zeroPrefix = true;
            for (int index = 0; index < bytes.length - 1; index++) zeroPrefix &= bytes[index] == 0;
            boolean allZero = zeroPrefix && bytes[bytes.length - 1] == 0;
            boolean loopback = zeroPrefix && bytes[bytes.length - 1] == 1;
            boolean ipv4Mapped = true;
            for (int index = 0; index < 10; index++) ipv4Mapped &= bytes[index] == 0;
            ipv4Mapped &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            if (ipv4Mapped) {
                String ipv4 = (bytes[12] & 0xff) + "." + (bytes[13] & 0xff) + "."
                        + (bytes[14] & 0xff) + "." + (bytes[15] & 0xff);
                return isLocalOrPrivateNumericHost(ipv4);
            }
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return allZero || loopback || (first & 0xfe) == 0xfc
                    || (first == 0xfe && (second & 0xc0) == 0x80)
                    || (first == 0xfe && (second & 0xc0) == 0xc0)
                    || first == 0xff
                    || (first == 0x20 && second == 0x01 && bytes[2] == 0x0d && bytes[3] == (byte) 0xb8);
        } catch (java.net.UnknownHostException exception) {
            return true;
        }
    }

    static String toText(String html) {
        if (html == null || html.isEmpty()) return null;
        return Jsoup.parseBodyFragment(html).text();
    }
}
