package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.CollectionBatch;

/** Each source provides normalized observations. A failing source must not stop other sources. */
public interface MonitoringConnector {
    String sourceName();
    CollectionBatch collect();
}
