package ae.sharjah.digital.observability.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpStatusCodeException;

/** Extract a vendor Retry-After without logging headers or response bodies. */
final class RateLimitRetry {
    private RateLimitRetry() { }

    // -1 = not rate limited; 0 = rate limited, no usable Retry-After.
    static long afterMillis(Throwable failure, Instant now) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof HttpStatusCodeException) {
                HttpStatusCodeException http = (HttpStatusCodeException) cause;
                if (http.getRawStatusCode() != 429) return -1L;
                HttpHeaders headers = http.getResponseHeaders();
                return parse(headers == null ? null : headers.getFirst("Retry-After"), now);
            }
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return -1L;
    }

    static long parse(String header, Instant now) {
        if (header == null) return 0L;
        String value = header.trim();
        try {
            return TimeUnit.SECONDS.toMillis(Math.max(0L, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            try {
                Instant retry = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0L, Duration.between(now, retry).toMillis());
            } catch (RuntimeException invalid) {
                return 0L;
            }
        }
    }
}
