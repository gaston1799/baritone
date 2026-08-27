package baritone.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.Collections;

/**
 * Appends to baritone/corrections.log (in the game directory) whenever baritone
 * has to correct itself: rewinding/skipping path positions, snapping back to the
 * path, cancelling for being off-path, or moving backward to fix an overshoot.
 * The reason is recorded so you can see WHY the correction happened.
 */
public final class CorrectionLogger {

    private static volatile long lastLogMs = 0;
    private static String lastMessage = "";
    private static long lastMessageMs;

    private CorrectionLogger() {}

    /**
     * Append one correction event. Identical hot-loop messages are coalesced so
     * a reconciliation bug cannot write the same line every client tick.
     */
    public static synchronized void log(String message) {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < 1) {
            return;
        }
        if (message.equals(lastMessage) && now - lastMessageMs < 500) {
            return;
        }
        lastLogMs = now;
        lastMessage = message;
        lastMessageMs = now;
        append(message);
    }

    /** Append a diagnostic event without coalescing repeated messages. */
    public static synchronized void logAlways(String message) {
        append(message);
    }

    private static void append(String message) {
        try {
            Files.write(
                    Paths.get(System.getProperty("user.dir"), "baritone", "corrections.log"),
                    Collections.singletonList("[" + LocalTime.now().withNano(0) + "] " + message),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
            // logging must never break pathing
        }
    }
}
