/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.behavior.SelfDefenceBehavior;
import baritone.utils.combat.CombatFailureLogger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class LastFailCommand extends Command {

    public LastFailCommand(IBaritone baritone) {
        super(baritone, "lastfail", "combatfail");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        SelfDefenceBehavior behavior = ((Baritone) baritone).getSelfDefenceBehavior();
        String mode = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "summary";
        if ("clear".equals(mode)) {
            behavior.clearCombatFailures();
            logDirect("Cleared in-memory combat failure history (the JSONL file was preserved)");
            return;
        }

        Optional<CombatFailureLogger.Failure> latest = behavior.getLatestCombatFailure();
        if (latest.isEmpty()) {
            logDirect("No combat failure has been recorded in this session");
            return;
        }
        CombatFailureLogger.Failure failure = latest.get();
        if ("render".equals(mode)) {
            behavior.renderLatestCombatFailure(200);
            logDirect("Rendering the latest failure for 10 seconds: white=player, cyan=target");
            return;
        }
        if ("frames".equals(mode)) {
            logFrames(failure);
            return;
        }
        if (!"summary".equals(mode)) {
            logDirect("Usage: #lastfail [summary|frames|render|clear]");
            return;
        }
        Path file = behavior.getCombatFailureLogPath();
        logDirect(String.format(
                "Last combat failure: %s\nReason: %s\nDetail: %s\nTrace: %d frames\nFile: %s",
                failure.time(), failure.reason(), failure.detail(), failure.frames().size(),
                file == null ? "unavailable" : file));
    }

    private void logFrames(CombatFailureLogger.Failure failure) {
        List<CombatFailureLogger.Frame> frames = failure.frames();
        int start = Math.max(0, frames.size() - 12);
        StringBuilder text = new StringBuilder("Last ")
                .append(frames.size() - start)
                .append(" frames for ")
                .append(failure.reason())
                .append(':');
        for (int i = start; i < frames.size(); i++) {
            CombatFailureLogger.Frame frame = frames.get(i);
            text.append(String.format(Locale.US,
                    "\nt=%d %-20s p=(%.2f,%.2f,%.2f) v=(%.2f,%.2f,%.2f) d=%.2f cd=%.2f",
                    frame.tick(), frame.action(),
                    frame.playerX(), frame.playerY(), frame.playerZ(),
                    frame.playerVelocityX(), frame.playerVelocityY(), frame.playerVelocityZ(),
                    frame.distance(), frame.cooldown()));
        }
        logDirect(text.toString());
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasAtMostOne()) {
            String prefix = args.hasAny() ? args.peekString().toLowerCase(Locale.US) : "";
            return Stream.of("summary", "frames", "render", "clear")
                    .filter(option -> option.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Inspect the latest combat failure trace";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Displays the latest bounded self-defence failure trace.",
                "The complete structured traces are appended to baritone/combat-failures.jsonl.",
                "",
                "Usage:",
                "> lastfail - Show the latest failure summary",
                "> lastfail frames - Show the final twelve trace frames",
                "> lastfail render - Render player and target traces for ten seconds",
                "> lastfail clear - Clear only the in-memory history"
        );
    }
}
