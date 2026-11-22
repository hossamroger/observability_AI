package ae.sharjah.digital.observability;

import ae.sharjah.digital.observability.connector.DemoMonitoringConnector;
import ae.sharjah.digital.observability.domain.CollectionBatch;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DemoMonitoringConnectorTest {
    @Test void demoIsExplicitlyMarkedAndPlatformSpecific() {
        CollectionBatch batch = new DemoMonitoringConnector().collect();
        assertEquals(6, batch.observations.size());
        assertTrue(batch.observations.stream().allMatch(metric -> metric.demo));
        assertTrue(batch.observations.stream().anyMatch(metric -> "ANDROID".equals(metric.platform)));
        assertTrue(batch.observations.stream().anyMatch(metric -> "IOS".equals(metric.platform)));
        assertTrue(batch.observations.stream().anyMatch(metric -> "WEB".equals(metric.platform)));
    }
}
