package utb.fai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Vícevláknový web crawler.
 *
 * Každá stránka se stahuje a analyzuje v samostatné úloze v {@link ExecutorService}.
 * Hloubka se předává s každou úlohou; odkazy ze stránek v hloubce maxDepth se už
 * nezařazují. Ukončení hlídá počítadlo rozpracovaných úloh + {@link CountDownLatch},
 * celkový časový limit a maximální počet stránek.
 */
public class Crawler {

    /** Pojistka: maximální počet stránek, které se zařadí ke stažení. */
    public static final int MAX_PAGES = 500;

    /** Pojistka: celkový časový limit procházení. */
    public static final long TOTAL_TIMEOUT_SECONDS = 60;

    /** Stahování je převážně I/O, proto víc vláken, než je jader. */
    static final int THREADS_PER_CPU = 4;

    /** Přípony souborů, které nemá smysl stahovat jako HTML. */
    private static final Pattern FILE_EXTENSION = Pattern.compile(
            ".*\\.(pdf|jpe?g|png|gif|bmp|svg|webp|ico|tiff?|zip|rar|7z|gz|tgz|bz2|xz|tar|exe|msi|dmg|iso|apk|jar"
                    + "|mp3|mp4|m4a|avi|mov|mkv|wmv|flv|webm|ogg|wav|flac"
                    + "|docx?|xlsx?|pptx?|odt|ods|odp|rtf|csv|txt|xml|json|css|js|woff2?|ttf|eot)$");

    private final PageFetcher fetcher;
    private final int maxDepth;
    private final Consumer<Document> pageAnalyzer;
    private final int maxPages;
    private final long totalTimeoutSeconds;

    /** Navštívené (= zařazené ke zpracování) URL. Každá URL se zpracuje nejvýše jednou. */
    private final Set<String> visitedURIs = ConcurrentHashMap.newKeySet();

    /** Všechny nalezené odkazy (normalizované, http/https), včetně opakovaných výskytů. */
    private final Collection<String> foundURIs = new ConcurrentLinkedQueue<>();

    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger scheduledPages = new AtomicInteger();
    private final AtomicInteger analyzedPages = new AtomicInteger();
    private final AtomicBoolean pageLimitReported = new AtomicBoolean();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean stopping = false;

    private ExecutorService executor;

    /**
     * @param pageAnalyzer volá se pro každou úspěšně staženou stránku, a to
     *                     souběžně z více vláken - musí být thread-safe
     */
    public Crawler(PageFetcher fetcher, int maxDepth, Consumer<Document> pageAnalyzer) {
        this(fetcher, maxDepth, pageAnalyzer, MAX_PAGES, TOTAL_TIMEOUT_SECONDS);
    }

    public Crawler(PageFetcher fetcher, int maxDepth, Consumer<Document> pageAnalyzer,
            int maxPages, long totalTimeoutSeconds) {
        this.fetcher = fetcher;
        this.maxDepth = maxDepth;
        this.pageAnalyzer = pageAnalyzer;
        this.maxPages = maxPages;
        this.totalTimeoutSeconds = totalTimeoutSeconds;
    }

    /**
     * Spustí procházení od startovní URL a počká na jeho dokončení (nebo na
     * vypršení celkového limitu). Metodu lze na jedné instanci zavolat jen jednou.
     *
     * @return true, pokud procházení doběhlo celé; false při vypršení limitu
     */
    public boolean crawl(String startUrl) throws InterruptedException {
        String start = normalize(startUrl);
        if (start == null) {
            throw new IllegalArgumentException("Startovní URL nelze procházet: " + startUrl);
        }

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() * THREADS_PER_CPU);
        AtomicInteger threadNo = new AtomicInteger();
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "crawler-" + threadNo.incrementAndGet());
            t.setDaemon(true); // visící spojení nesmí zablokovat ukončení JVM
            return t;
        });

        long startTime = System.nanoTime();
        visitedURIs.add(start);
        schedule(start, 0);

        boolean finished = done.await(totalTimeoutSeconds, TimeUnit.SECONDS);
        stopping = true;
        if (finished) {
            executor.shutdown();
        } else {
            Log.info("Vypršel celkový časový limit " + totalTimeoutSeconds
                    + " s, ukončuji procházení (rozpracováno úloh: " + pendingTasks.get() + ").");
            executor.shutdownNow();
        }
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            Log.info("Některá vlákna stále běží, ignoruji je (jsou daemon).");
        }

        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        Log.info("Hotovo: analyzováno " + analyzedPages.get() + " stránek, zařazeno "
                + scheduledPages.get() + ", nalezeno " + foundURIs.size() + " odkazů ("
                + visitedURIs.size() + " unikátních), " + threads + " vláken, " + ms + " ms.");
        return finished;
    }

    /** Zařadí URL ke zpracování. URL už musí být přidaná do visitedURIs. */
    private void schedule(String url, int depth) {
        if (stopping) {
            return;
        }
        // atomicky rezervujeme jedno místo z limitu maxPages
        int n;
        do {
            n = scheduledPages.get();
            if (n >= maxPages) {
                if (pageLimitReported.compareAndSet(false, true)) {
                    Log.info("Dosažen limit " + maxPages + " stránek, další odkazy už nezařazuji.");
                }
                return;
            }
        } while (!scheduledPages.compareAndSet(n, n + 1));
        pendingTasks.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    processPage(url, depth);
                } catch (Throwable t) {
                    Log.info("Chyba při zpracování " + url + ": " + t);
                } finally {
                    taskFinished();
                }
            });
        } catch (RejectedExecutionException e) {
            taskFinished(); // executor už se vypíná
        }
    }

    private void taskFinished() {
        if (pendingTasks.decrementAndGet() == 0) {
            done.countDown();
        }
    }

    private void processPage(String url, int depth) {
        if (stopping) {
            return;
        }
        Log.info("Analyzing [" + depth + "] " + url);
        Document doc = fetcher.fetch(url);
        if (doc == null) {
            return;
        }

        // po přesměrování může jít o stránku, kterou už zpracovává jiná úloha
        String finalUrl = normalize(doc.location());
        if (finalUrl != null && !finalUrl.equals(url) && !visitedURIs.add(finalUrl)) {
            Log.info("Přeskakuji " + url + ": přesměrováno na již navštívenou " + finalUrl);
            return;
        }

        // Odkazy zpracujeme PŘED analýzou - analyzátor smí dokument měnit
        // (např. odstraní <noscript>, ve kterém mohou být odkazy).
        // Odkazy z poslední hloubky už nezařazujeme.
        if (depth < maxDepth) {
            for (Element el : doc.select("a[href], area[href], frame[src], iframe[src]")) {
                String abs = el.hasAttr("href") ? el.attr("abs:href") : el.attr("abs:src");
                String link = normalize(abs);
                if (link == null) {
                    continue;
                }
                foundURIs.add(link);
                if (visitedURIs.add(link)) {
                    Log.debug("Adding URI [" + (depth + 1) + "] " + link + " (z " + url + ")");
                    schedule(link, depth + 1);
                }
            }
        }

        if (stopping) {
            return; // po vypršení limitu už výsledky neměníme
        }
        pageAnalyzer.accept(doc);
        analyzedPages.incrementAndGet();
    }

    /**
     * Normalizuje URL: jen http/https, malá písmena ve schématu a hostu, bez
     * výchozího portu, bez #fragmentu, prázdná cesta = "/", u ostatních cest
     * se odstraní koncové lomítko. Odkazy na soubory (.pdf, .jpg...) odmítne.
     *
     * @return normalizovaná URL, nebo null, pokud ji nemáme procházet
     */
    static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null; // abs:href vrací "" pro mailto:, javascript: apod. nebo nevalidní URL
        }
        URI uri;
        try {
            uri = new URI(url.trim().replace(" ", "%20")).normalize(); // odstraní "./" a "../"
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.isOpaque()) {
            return null; // mailto:, tel:, javascript:, data: ... jsou "opaque"
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);

        int port = uri.getPort();
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            port = -1;
        }

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        } else {
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        if (FILE_EXTENSION.matcher(path.toLowerCase(Locale.ROOT)).matches()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        if (uri.getRawUserInfo() != null) {
            sb.append(uri.getRawUserInfo()).append('@');
        }
        sb.append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        return sb.toString();
    }

    public Set<String> getVisitedURIs() {
        return visitedURIs;
    }

    public Collection<String> getFoundURIs() {
        return foundURIs;
    }

    public int getAnalyzedPages() {
        return analyzedPages.get();
    }
}
