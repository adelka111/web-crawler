package utb.fai;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Stahuje HTML stránky a správně určuje jejich kódování.
 *
 * Pořadí určení kódování:
 * <ol>
 * <li>charset z HTTP hlavičky Content-Type,</li>
 * <li>{@code <meta charset>} nebo {@code <meta http-equiv="Content-Type">} v HTML,</li>
 * <li>fallback UTF-8.</li>
 * </ol>
 * Stránka se stáhne jako surové bajty, dekóduje zjištěným kódováním a teprve
 * potom se parsuje přes {@link Jsoup#parse(String, String)}.
 *
 * Třída je bezstavová, takže ji lze sdílet mezi vlákny.
 */
public class PageFetcher {

    public static final int DEFAULT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MAX_BODY_BYTES = 5 * 1024 * 1024;
    public static final String DEFAULT_USER_AGENT = "UTB-FAI-WebCrawler/1.0 (school project; Java "
            + System.getProperty("java.version") + ")";

    /** Kolik bajtů ze začátku stránky prohledáváme kvůli META tagu s kódováním. */
    static final int META_SCAN_BYTES = 8 * 1024;

    private static final Pattern CHARSET_IN_CONTENT = Pattern
            .compile("charset\\s*=\\s*[\"']?([^\\s;\"']+)", Pattern.CASE_INSENSITIVE);

    private final int timeoutMs;
    private final int maxBodyBytes;
    private final String userAgent;

    public PageFetcher() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_BODY_BYTES, DEFAULT_USER_AGENT);
    }

    public PageFetcher(int timeoutMs, int maxBodyBytes, String userAgent) {
        this.timeoutMs = timeoutMs;
        this.maxBodyBytes = maxBodyBytes;
        this.userAgent = userAgent;
    }

    /**
     * Stáhne a naparsuje stránku.
     *
     * @return naparsovaný dokument (jeho {@code location()} je finální URL po
     *         přesměrování), nebo {@code null}, pokud stránku nešlo stáhnout,
     *         nejde o text/html nebo nastala jiná chyba. Chyba je vypsaná na
     *         System.err, výjimka nikdy nepropadne ven.
     */
    public Document fetch(String url) {
        try {
            Connection.Response response = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .maxBodySize(maxBodyBytes)
                    .followRedirects(true)
                    .ignoreContentType(false)
                    .ignoreHttpErrors(false)
                    .execute();

            String contentType = response.contentType();
            if (!isHtml(contentType)) {
                Log.info("Přeskakuji " + url + ": není text/html (" + contentType + ")");
                return null;
            }

            byte[] body = response.bodyAsBytes();
            Charset charset = detectCharset(response.charset(), body);
            Log.debug("Staženo " + url + ": " + body.length + " B, kódování " + charset
                    + (response.charset() != null ? " (hlavička: " + response.charset() + ")" : " (META/výchozí)"));
            String html = new String(body, charset);
            if (!html.isEmpty() && html.charAt(0) == '\uFEFF') {
                html = html.substring(1); // BOM
            }

            // finální URL (po přesměrování) jako základ pro relativní odkazy
            String baseUri = response.url().toString();
            Document doc = Jsoup.parse(html, baseUri);
            doc.charset(charset);
            return doc;
        } catch (HttpStatusException e) {
            Log.info("Chyba při stahování " + url + ": HTTP " + e.getStatusCode());
        } catch (UnsupportedMimeTypeException e) {
            Log.info("Přeskakuji " + url + ": nepodporovaný typ obsahu (" + e.getMimeType() + ")");
        } catch (SocketTimeoutException | HttpTimeoutException e) {
            Log.info("Chyba při stahování " + url + ": vypršel časový limit");
        } catch (IOException e) {
            Log.info("Chyba při stahování " + url + ": " + e);
        } catch (Exception e) {
            // např. IllegalArgumentException pro nevalidní URL, chyby parseru apod.
            Log.info("Neočekávaná chyba u " + url + ": " + e);
        }
        return null;
    }

    static boolean isHtml(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mime.equals("text/html");
    }

    /**
     * Určí kódování: hlavička → META tag → UTF-8.
     *
     * @param headerCharset charset z hlavičky Content-Type (může být null)
     */
    static Charset detectCharset(String headerCharset, byte[] body) {
        Charset cs = toCharset(headerCharset);
        if (cs != null) {
            return cs;
        }
        cs = charsetFromMeta(body);
        if (cs != null) {
            return cs;
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Najde kódování v META tagu na začátku dokumentu. Začátek se dekóduje jako
     * ISO-8859-1, což je pro ASCII značky bezpečné u všech běžných kódování
     * (UTF-8, windows-1250, ISO-8859-2...).
     */
    static Charset charsetFromMeta(byte[] body) {
        int len = Math.min(body.length, META_SCAN_BYTES);
        String head = new String(body, 0, len, StandardCharsets.ISO_8859_1);
        Document doc = Jsoup.parse(head);
        for (Element meta : doc.select("meta")) {
            if (meta.hasAttr("charset")) {
                Charset cs = toCharset(meta.attr("charset"));
                if (cs != null) {
                    return cs;
                }
            } else if (meta.attr("http-equiv").equalsIgnoreCase("content-type")) {
                Matcher m = CHARSET_IN_CONTENT.matcher(meta.attr("content"));
                if (m.find()) {
                    Charset cs = toCharset(m.group(1));
                    if (cs != null) {
                        return cs;
                    }
                }
            }
        }
        return null;
    }

    /** Převede název kódování na Charset; neznámé nebo nevalidní vrací null. */
    static Charset toCharset(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().replace("\"", "").replace("'", "");
        if (n.isEmpty()) {
            return null;
        }
        try {
            return Charset.forName(n);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return null;
        }
    }
}
