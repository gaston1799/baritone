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

    private CorrectionLogger() {}

    /**
     * Append one correction event. Cheap enough to call from hot paths; a 1-ms
     * minimum spacing prevents any spam flood from filling the file.
     */
    public static void log(String message) {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < 1) {
            return;
        }
        lastLogMs = now;
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
