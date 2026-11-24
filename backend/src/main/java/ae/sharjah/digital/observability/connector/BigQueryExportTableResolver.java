package ae.sharjah.digital.observability.connector;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Select exactly one GA4 export per property-local day. Never combine daily and intraday for the same day. */
final class BigQueryExportTableResolver {
    private final RestTemplate http;
    private final String project;
    private final String dataset;

    BigQueryExportTableResolver(RestTemplate http, String project, String dataset) {
        this.http = http;
        this.project = project;
        this.dataset = dataset;
    }

    List<String> resolve(Instant start, Instant end, ZoneId zone, HttpHeaders headers) {
        List<String> tables = new ArrayList<>();
        for (LocalDate date : exportDates(start, end, zone)) {
            String suffix = date.format(DateTimeFormatter.BASIC_ISO_DATE);
            String daily = "events_" + suffix;
            String intraday = "events_intraday_" + suffix;
            // Daily export replaces intraday when available; never query both for a day.
            String selected;
            if (exists(daily, headers)) selected = daily;
            else if (exists(intraday, headers)) selected = intraday;
            else throw new BigQueryCollectionException(BigQueryCollectionException.Category.EXPORT_TABLE_UNAVAILABLE);
            tables.add(project + "." + dataset + "." + selected);
        }
        return tables;
    }

    static List<LocalDate> exportDates(Instant start, Instant end, ZoneId zone) {
        if (!start.isBefore(end)) throw new IllegalArgumentException("Invalid export window");
        List<LocalDate> dates = new ArrayList<>();
        LocalDate last = end.minusNanos(1).atZone(zone).toLocalDate();
        for (LocalDate date = start.atZone(zone).toLocalDate(); !date.isAfter(last); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }

    private boolean exists(String table, HttpHeaders headers) {
        URI uri = UriComponentsBuilder.fromHttpUrl("https://bigquery.googleapis.com/bigquery/v2/projects")
                .pathSegment(project, "datasets", dataset, "tables", table)
                .queryParam("fields", "tableReference")
                .build().encode().toUri();
        try {
            http.exchange(uri, HttpMethod.GET, new HttpEntity<Void>(headers), String.class);
            return true;
        } catch (HttpClientErrorException.NotFound missing) {
            return false;
        }
    }
}
