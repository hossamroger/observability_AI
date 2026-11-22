package ae.sharjah.digital.observability.domain;

import java.util.Collections;
import java.util.List;

public class CollectionBatch {
    public final String sourceSystem;
    public final List<MetricObservation> observations;

    public CollectionBatch(String sourceSystem, List<MetricObservation> observations) {
        this.sourceSystem = sourceSystem;
        this.observations = Collections.unmodifiableList(observations);
    }
}
