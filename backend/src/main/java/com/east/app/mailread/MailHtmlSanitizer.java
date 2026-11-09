package com.east.app.mailread;

import com.east.app.gmail.GmailContentId;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
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

    private static final Safelist ALLOWED = Safelist.none()
            .addTags("a", "b", "blockquote", "br", "caption", "code", "dd", "div", "dl", "dt",
                    "em", "h1", "h2", "h3", "h4", "hr", "i", "img", "li", "ol", "p", "pre",
                    "small", "span", "strong", "sub", "sup", "table", "tbody", "td", "th",
                    "thead", "tr", "u", "ul")
            .addAttributes("a", "href", "title", "target", "rel")
            .addAttributes("img", "src", "alt", "title", "width", "height", "data-east-external-src")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addProtocols("a", "href", "http", "https", "mailto");

    private MailHtmlSanitizer() { }

    static String sanitize(String html) {
        return sanitize(html, Map.of());
    }

    /** Keeps CID images local and leaves remote image URLs inert until the user opts in. */
    static String sanitize(String html, Map<String, String> localInlineImageUrls) {
        if (html == null || html.isEmpty()) return null;
        Document source = Jsoup.parseBodyFragment(html);
        prepareImages(source, localInlineImageUrls);
        Document cleaned = new Cleaner(ALLOWED).clean(source);
        cleaned.select("a[href]").forEach(link -> {
            link.attr("target", "_blank");
            link.attr("rel", "noopener noreferrer");
        });
        return cleaned.body().html();
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
