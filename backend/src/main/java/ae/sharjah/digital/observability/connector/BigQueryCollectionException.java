package ae.sharjah.digital.observability.connector;

/** Safe diagnostic categories: no vendor payloads, identifiers or credentials. */
public final class BigQueryCollectionException extends IllegalStateException {
    public enum Category {
        INVALID_CONFIG, QUERY_ERRORS, QUERY_PENDING, PAGINATED_RESULT, INVALID_RESULT, EXPORT_TABLE_UNAVAILABLE
    }

    private final Category category;

    public BigQueryCollectionException(Category category) {
        super("BIGQUERY_" + category.name());
        this.category = category;
    }

    public String category() {
        return "BIGQUERY_" + category.name();
    }
}
