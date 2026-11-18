package utb.fai;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;

/**
 * Thread-safe počítadlo četnosti slov v textu čitelném pro uživatele.
 * Instanci lze předat jako analyzátor do {@link Crawler}.
 */
public class WordCounter implements Consumer<Document> {

    /**
     * Slovo = posloupnost písmen (libovolná abeceda) včetně kombinujících
     * diakritických znamének. Čísla a interpunkce slova oddělují.
     */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+");

    /** Elementy, jejichž obsah uživatel nevidí jako text. */
    private static final String INVISIBLE = "script, style, noscript, template";

    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();

    /** Analyzuje stránku. POZOR: odstraní z dokumentu neviditelné elementy. */
    @Override
    public void accept(Document doc) {
        doc.select(INVISIBLE).remove();
        Element body = doc.body();
        if (body != null) {
            addText(visibleText(body));
        }
    }

    /**
     * Text elementu jako u {@link Element#text()}, ale jednotlivé textové uzly
     * odděluje mezerou. {@code text()} totiž sousední inline elementy slepí
     * (např. {@code <a>Reklama</a><a>Řešení</a>} → "ReklamaŘešení").
     */
    static String visibleText(Element root) {
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof TextNode) {
                sb.append(((TextNode) node).text()).append(' ');
            }
        }, root);
        return sb.toString();
    }

    /** Započítá všechna slova z textu. */
    public void addText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // sjednotí rozložené znaky (např. "c" + háček) na složené ("č")
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        Matcher m = WORD.matcher(normalized);
        while (m.find()) {
            String word = m.group().toLowerCase(Locale.ROOT);
            counts.computeIfAbsent(word, k -> new LongAdder()).increment();
        }
    }

    /**
     * Vrátí nejvýše {@code n} nejčastějších slov seřazených sestupně podle
     * počtu; při shodě počtu abecedně (kvůli deterministickému výstupu).
     */
    public List<Map.Entry<String, Long>> top(int n) {
        return counts.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(n)
                .collect(Collectors.toList());
    }

    /** Počet různých slov. */
    public int distinctWords() {
        return counts.size();
    }
}
