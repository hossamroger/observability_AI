package ae.sharjah.digital.observability.service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitRetryTest {
    private final Instant now = Instant.parse("2026-11-25T06:15:00Z");

    @Test void parsesNumericAndDateHeaders() {
        assertEquals(300000L, RateLimitRetry.parse("300", now));
        assertEquals(600000L, RateLimitRetry.parse("Wed, 25 Nov 2026 06:25:00 GMT", now));
        assertEquals(0L, RateLimitRetry.parse("invalid", now));
        assertEquals(0L, RateLimitRetry.parse(null, now));
    }

    @Test void recognizesWrappedHttp429Only() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "120");
        HttpClientErrorException error = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limited", headers, new byte[0], StandardCharsets.UTF_8);
        assertEquals(120000L, RateLimitRetry.afterMillis(new IllegalStateException(error), now));
        assertEquals(-1L, RateLimitRetry.afterMillis(new HttpClientErrorException(HttpStatus.FORBIDDEN), now));
    }
}
