package com.example.graduationprocessbe.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;

class CalendarDateRangeTest {
    private void validate(String start,String end) {
        CalendarDateRange.validate(OffsetDateTime.parse(start),OffsetDateTime.parse(end),
                LocalDate.parse("2026-09-01"),LocalDate.parse("2026-12-31"),"Ngoài học kỳ");
    }

    @Test void acceptsBothCalendarBoundariesInVietnamTime() {
        assertDoesNotThrow(() -> validate("2026-08-31T17:00:00Z","2026-12-31T16:59:59Z"));
    }
    @Test void rejectsBeforeFirstDay() {
        assertThrows(IllegalArgumentException.class,() -> validate("2026-08-31T16:59:59Z","2026-09-02T00:00:00+07:00"));
    }
    @Test void rejectsMidnightAfterLastDay() {
        assertThrows(IllegalArgumentException.class,() -> validate("2026-09-01T00:00:00+07:00","2026-12-31T17:00:00Z"));
    }
    @Test void rejectsEqualAndReversedTimes() {
        assertThrows(IllegalArgumentException.class,() -> validate("2026-09-01T00:00:00+07:00","2026-09-01T00:00:00+07:00"));
        assertThrows(IllegalArgumentException.class,() -> validate("2026-09-02T00:00:00+07:00","2026-09-01T00:00:00+07:00"));
    }
}
