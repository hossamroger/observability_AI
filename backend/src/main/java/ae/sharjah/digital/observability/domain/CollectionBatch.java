package ae.sharjah.digital.observability.domain;

import java.util.Collections;
import java.util.List;

public class CollectionBatch {
    public final String sourceSystem;
    public final List<MetricObservation> observations;
    public final boolean partial;

    public CollectionBatch(String sourceSystem, List<MetricObservation> observations) {
        this(sourceSystem, observations, false);
    }

    public CollectionBatch(String sourceSystem, List<MetricObservation> observations, boolean partial) {
        this.sourceSystem = sourceSystem;
        this.observations = Collections.unmodifiableList(new java.util.ArrayList<>(observations));
        this.partial = partial;
    }
}
