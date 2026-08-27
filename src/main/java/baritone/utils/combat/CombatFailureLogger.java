/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.utils.combat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Bounded in-memory flight recorder for combat. A failure freezes a copy of
 * the recent frames; callers may then append the JSON representation to disk.
 */
public final class CombatFailureLogger {

    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Deque<Failure> failures = new ArrayDeque<>();
    private int maxFrames;
    private final int maxFailures;

    public CombatFailureLogger(int maxFrames, int maxFailures) {
        this.maxFrames = clampFrames(maxFrames);
        this.maxFailures = Math.max(1, maxFailures);
    }

    public void setMaxFrames(int maxFrames) {
        this.maxFrames = clampFrames(maxFrames);
        trimFrames();
    }

    public void beginTrace() {
        frames.clear();
    }

    public void capture(Frame frame) {
        if (frame == null) {
            return;
        }
        frames.addLast(frame);
        trimFrames();
    }

    public Failure record(String reason, String detail) {
        return record(reason, detail, "", Collections.emptyList());
    }

    /**
     * Records a failure with optional decision context and the full candidate
     * breakdown evaluated when it happened, so the JSONL can be used to tune
     * the combat movement planner.
     */
    public Failure record(String reason, String detail, String context, List<CandidateInfo> candidates) {
        Failure failure = new Failure(Instant.now(), reason, detail, context,
                candidates == null ? Collections.emptyList()
                        : Collections.unmodifiableList(new ArrayList<>(candidates)),
                Collections.unmodifiableList(new ArrayList<>(frames)));
        failures.addLast(failure);
        while (failures.size() > maxFailures) {
            failures.removeFirst();
        }
        return failure;
    }

    public Optional<Failure> latest() {
        return Optional.ofNullable(failures.peekLast());
    }

    public void clearFailures() {
        failures.clear();
    }

    public static String toJson(Failure failure) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{')
                .append("\"time\":\"").append(escape(failure.time().toString())).append("\",")
                .append("\"reason\":\"").append(escape(failure.reason())).append("\",")
                .append("\"detail\":\"").append(escape(failure.detail())).append("\",")
                .append("\"context\":\"").append(escape(failure.context())).append("\",")
                .append("\"candidates\":[");
        for (int i = 0; i < failure.candidates().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            CandidateInfo candidate = failure.candidates().get(i);
            json.append('{')
                    .append("\"move\":\"").append(escape(candidate.move())).append("\",")
                    .append("\"score\":").append(number(candidate.score())).append(',')
                    .append("\"safe\":").append(candidate.safe()).append(',')
                    .append("\"reason\":").append(candidate.reason() == null ? "null"
                            : '"' + escape(candidate.reason()) + '"')
                    .append('}');
        }
        json.append("],\"frames\":[");
        for (int i = 0; i < failure.frames().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Frame frame = failure.frames().get(i);
            json.append('{')
                    .append("\"tick\":").append(frame.tick()).append(',')
                    .append("\"action\":\"").append(escape(frame.action())).append("\",")
                    .append("\"player\":").append(vector(frame.playerX(), frame.playerY(), frame.playerZ())).append(',')
                    .append("\"playerVelocity\":").append(vector(frame.playerVelocityX(), frame.playerVelocityY(), frame.playerVelocityZ())).append(',')
                    .append("\"target\":").append(vector(frame.targetX(), frame.targetY(), frame.targetZ())).append(',')
                    .append("\"targetVelocity\":").append(vector(frame.targetVelocityX(), frame.targetVelocityY(), frame.targetVelocityZ())).append(',')
                    .append("\"distance\":").append(number(frame.distance())).append(',')
                    .append("\"health\":").append(number(frame.health())).append(',')
                    .append("\"cooldown\":").append(number(frame.cooldown())).append(',')
                    .append("\"yaw\":").append(number(frame.yaw())).append(',')
                    .append("\"pitch\":").append(number(frame.pitch())).append(',')
                    .append("\"fallDistance\":").append(number(frame.fallDistance())).append(',')
                    .append("\"grounded\":").append(frame.grounded()).append(',')
                    .append("\"fallFlying\":").append(frame.fallFlying()).append(',')
                    .append("\"weaponReady\":").append(frame.weaponReady()).append(',')
                    .append("\"lineOfSight\":").append(frame.lineOfSight()).append(',')
                    .append("\"targetHealth\":").append(number(frame.targetHealth()))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private void trimFrames() {
        while (frames.size() > maxFrames) {
            frames.removeFirst();
        }
    }

    private static int clampFrames(int value) {
        return Math.max(20, Math.min(200, value));
    }

    private static String vector(double x, double y, double z) {
        return '[' + number(x) + ',' + number(y) + ',' + number(z) + ']';
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.US, "%.5f", value) : "null";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    public record Frame(
            int tick,
            String action,
            double playerX,
            double playerY,
            double playerZ,
            double playerVelocityX,
            double playerVelocityY,
            double playerVelocityZ,
            double targetX,
            double targetY,
            double targetZ,
            double targetVelocityX,
            double targetVelocityY,
            double targetVelocityZ,
            double distance,
            double health,
            double cooldown,
            double yaw,
            double pitch,
            double fallDistance,
            boolean grounded,
            boolean fallFlying,
            boolean weaponReady,
            boolean lineOfSight,
            double targetHealth
    ) {
    }

    public record CandidateInfo(String move, double score, boolean safe, String reason) {
    }

    public record Failure(
            Instant time,
            String reason,
            String detail,
            String context,
            List<CandidateInfo> candidates,
            List<Frame> frames
    ) {
    }
}
