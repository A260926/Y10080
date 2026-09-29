package org.example.retention;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class Clock {
    public static final String DEFAULT_TIME = "2026-09-29T00:00:00Z";
    private static final String KEY = "clock_now";

    private final Database database;

    public Clock(Database database) {
        this.database = database;
    }

    public String now() {
        try {
            PreparedStatement statement = database.connection().prepareStatement("SELECT value FROM app_state WHERE key = ?");
            statement.setString(1, KEY);
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return normalize(resultSet.getString(1));
            }
            set(DEFAULT_TIME);
            return DEFAULT_TIME;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read controlled clock", exception);
        }
    }

    public String set(String instant) {
        String normalized = normalize(instant);
        try {
            PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO app_state(key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """);
            statement.setString(1, KEY);
            statement.setString(2, normalized);
            statement.executeUpdate();
            return normalized;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot set controlled clock", exception);
        }
    }

    public String advance(String isoDuration) {
        try {
            Duration duration = Duration.parse(isoDuration);
            Instant updated = Instant.parse(now()).plus(duration);
            return set(updated.toString());
        } catch (DateTimeParseException exception) {
            throw new ApiException(400, "duration must be ISO-8601, for example PT1H or P2D");
        }
    }

    public String effectiveTime(Optional<String> requestTime) {
        return requestTime.map(Clock::normalize).orElseGet(this::now);
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "timestamp is required");
        }
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException exception) {
            throw new ApiException(400, "timestamp must be UTC ISO-8601 instant: " + value);
        }
    }

    public static long daysBetween(String from, String to) {
        return Duration.between(Instant.parse(from), Instant.parse(to)).toDays();
    }
}
