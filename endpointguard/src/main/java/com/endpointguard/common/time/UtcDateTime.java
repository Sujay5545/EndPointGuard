package com.endpointguard.common.time;

import java.time.Clock;
import java.time.LocalDateTime;

public final class UtcDateTime {

    private static final Clock CLOCK = Clock.systemUTC();

    private UtcDateTime() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(CLOCK);
    }
}
