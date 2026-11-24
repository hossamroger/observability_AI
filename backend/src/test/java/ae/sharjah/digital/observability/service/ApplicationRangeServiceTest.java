package ae.sharjah.digital.observability.service;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationRangeServiceTest {
    private final Instant latest = Instant.parse("2026-11-25T00:13:00Z");

    @Test void relativeRangeUsesAlignedEndAndExactDuration() {
        Instant[] range = ApplicationRangeService.resolveWindow(60, null, null, latest);
        assertEquals(Instant.parse("2026-11-24T23:10:00Z"), range[0]);
        assertEquals(Instant.parse("2026-11-25T00:10:00Z"), range[1]);
    }

    @Test void customRangePreservesExactInstants() {
        Instant[] range = ApplicationRangeService.resolveWindow(null, "2026-11-24T22:11:00Z", "2026-11-25T00:11:00Z", latest);
        assertEquals(Instant.parse("2026-11-24T22:11:00Z"), range[0]);
        assertEquals(Instant.parse("2026-11-25T00:11:00Z"), range[1]);
    }

    @Test void rejectsUnboundedFutureMixedAndUnsupportedRanges() {
        assertThrows(ResponseStatusException.class, () -> ApplicationRangeService.resolveWindow(11, null, null, latest));
        assertThrows(ResponseStatusException.class, () -> ApplicationRangeService.resolveWindow(30, "2026-11-24T22:11:00Z", null, latest));
        assertThrows(ResponseStatusException.class, () -> ApplicationRangeService.resolveWindow(null, "2026-11-01T00:00:00Z", latest.toString(), latest));
        assertThrows(ResponseStatusException.class, () -> ApplicationRangeService.resolveWindow(null, "2026-11-25T00:00:00Z", "2026-11-25T00:14:00Z", latest));
        assertThrows(ResponseStatusException.class, () -> ApplicationRangeService.resolveWindow(null, "2026-11-25T00:12:00Z", latest.toString(), latest));
    }
}
