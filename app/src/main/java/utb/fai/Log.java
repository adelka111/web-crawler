package utb.fai;

/**
 * Ladicí a průběžné výpisy na System.err podle úrovně ladění (args[2]).
 * <ul>
 * <li>0 = vypnuto (výchozí),</li>
 * <li>1 = navštívené URL, chyby stahování, přeskočené stránky, souhrn,</li>
 * <li>2 = navíc každý nově zařazený odkaz a zjištěné kódování stránek.</li>
 * </ul>
 * Po {@link #mute()} se už nic nevypisuje - vlákna crawleru, která po vypršení
 * časového limitu ještě doběhnou, tak nemohou nic vypsat za výslednou tabulku.
 */
final class Log {

    static final int OFF = 0;
    static final int INFO = 1;
    static final int DEBUG = 2;

    private static volatile int level = OFF;
    private static volatile boolean muted = false;

    private Log() {
    }

    static void setLevel(int newLevel) {
        level = newLevel;
    }

    static int getLevel() {
        return level;
    }

    /** Průběžné informace a chyby (úroveň ladění >= 1). */
    static void info(String message) {
        print(INFO, message);
    }

    /** Podrobné ladicí výpisy (úroveň ladění >= 2). */
    static void debug(String message) {
        print(DEBUG, message);
    }

    private static void print(int messageLevel, String message) {
        if (!muted && level >= messageLevel) {
            System.err.println(message);
        }
    }

    static void mute() {
        muted = true;
        System.err.flush();
    }
}
