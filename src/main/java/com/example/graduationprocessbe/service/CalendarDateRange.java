package com.example.graduationprocessbe.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Calendar dates include the entire last day in the school's timezone. */
final class CalendarDateRange {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private CalendarDateRange() {}

    static void validate(OffsetDateTime opens, OffsetDateTime closes, LocalDate startsOn, LocalDate endsOn, String message) {
        if (opens == null || closes == null || !opens.isBefore(closes))
            throw new IllegalArgumentException("Ngày giờ kết thúc phải sau ngày giờ bắt đầu");
        if (startsOn == null || endsOn == null)
            throw new IllegalArgumentException("Cần thiết lập ngày bắt đầu và kết thúc học kỳ");
        var start = startsOn.atStartOfDay(SCHOOL_ZONE).toInstant();
        var endExclusive = endsOn.plusDays(1).atStartOfDay(SCHOOL_ZONE).toInstant();
        if (opens.toInstant().isBefore(start) || !closes.toInstant().isBefore(endExclusive))
            throw new IllegalArgumentException(message);
    }
}
