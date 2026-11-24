package ae.sharjah.digital.observability.connector;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BigQueryExportTableResolverTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Dubai");
    private static final String URL = "https://bigquery.googleapis.com/bigquery/v2/projects/example/datasets/analytics/tables/";

    @Test void selectsDatesInPropertyTimezoneWithExclusiveEnd() {
        assertEquals(Arrays.asList(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)),
                BigQueryExportTableResolver.exportDates(Instant.parse("2026-11-24T19:40:00Z"), Instant.parse("2026-11-24T20:10:00Z"), ZONE));
        assertEquals(Collections.singletonList(LocalDate.of(2026, 11, 24)),
                BigQueryExportTableResolver.exportDates(Instant.parse("2026-11-24T19:30:00Z"), Instant.parse("2026-11-24T20:00:00Z"), ZONE));
    }

    @Test void prefersDailyAndFallsBackToIntradayWithoutOverlap() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL + "events_20261124?fields=tableReference")).andRespond(withSuccess());
        server.expect(requestTo(URL + "events_20261125?fields=tableReference")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL + "events_intraday_20261125?fields=tableReference")).andRespond(withSuccess());
        assertEquals(Arrays.asList("example.analytics.events_20261124", "example.analytics.events_intraday_20261125"),
                new BigQueryExportTableResolver(http, "example", "analytics").resolve(
                    Instant.parse("2026-11-24T19:40:00Z"), Instant.parse("2026-11-24T20:10:00Z"), ZONE, new HttpHeaders()));
        server.verify();
    }

    @Test void missingExportDoesNotSilentlyShortenWindow() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL + "events_20261124?fields=tableReference")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL + "events_intraday_20261124?fields=tableReference")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(BigQueryCollectionException.class, () ->
                new BigQueryExportTableResolver(http, "example", "analytics").resolve(
                    Instant.parse("2026-11-24T19:30:00Z"), Instant.parse("2026-11-24T20:00:00Z"), ZONE, new HttpHeaders()));
        server.verify();
    }
}
