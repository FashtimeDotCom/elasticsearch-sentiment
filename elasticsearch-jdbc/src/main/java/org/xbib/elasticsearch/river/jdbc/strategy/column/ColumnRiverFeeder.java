package org.xbib.elasticsearch.river.jdbc.strategy.column;

import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.indices.IndexMissingException;
import org.xbib.elasticsearch.plugin.feeder.jdbc.JDBCFeeder;
import org.xbib.pipeline.Pipeline;
import org.xbib.pipeline.PipelineProvider;
import org.xbib.pipeline.PipelineRequest;

import java.io.IOException;
import java.util.Map;

public class ColumnRiverFeeder<T, R extends PipelineRequest, P extends Pipeline<T, R>>
        extends JDBCFeeder<T, R, P> {

    private final static ESLogger logger = ESLoggerFactory.getLogger(ColumnRiverFeeder.class.getSimpleName());

    public ColumnRiverFeeder() {
        super();
    }

    @SuppressWarnings("rawtypes")
    public ColumnRiverFeeder(ColumnRiverFeeder feeder) {
        super(feeder);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public PipelineProvider<P> pipelineProvider() {
        return new PipelineProvider<P>() {
            @Override
            public P get() {
                return (P) new ColumnRiverFeeder(ColumnRiverFeeder.this);
            }
        };
    }

    @Override
    protected void createRiverContext(String riverType, String riverName, Map<String, Object> mySettings) throws IOException {
        super.createRiverContext(riverType, riverName, mySettings);
        // defaults for column strategy
        String columnCreatedAt = XContentMapValues.nodeStringValue(mySettings.get("created_at"), "created_at");
        String columnUpdatedAt = XContentMapValues.nodeStringValue(mySettings.get("updated_at"), "updated_at");
        String columnDeletedAt = XContentMapValues.nodeStringValue(mySettings.get("deleted_at"), null);
        boolean columnEscape = XContentMapValues.nodeBooleanValue(mySettings.get("column_escape"), true);
        TimeValue lastRunTimeStampOverlap = XContentMapValues.nodeTimeValue(mySettings.get("last_run_timestamp_overlap"),
                TimeValue.timeValueSeconds(0));
        riverContext
                .columnCreatedAt(columnCreatedAt)
                .columnUpdatedAt(columnUpdatedAt)
                .columnDeletedAt(columnDeletedAt)
                .columnEscape(columnEscape)
                .setLastRunTimeStampOverlap(lastRunTimeStampOverlap);
    }

    @Override
    protected void fetch() throws Exception {
        TimeValue lastRunTime = readLastRunTimeFromCustomInfo();
        TimeValue currentTime = new TimeValue(new java.util.Date().getTime());
        writeTimesToJdbcSettings(lastRunTime, currentTime);
        riverContext.getRiverSource().fetch();
        writeCustomInfo(currentTime.millis());
    }

    private TimeValue readLastRunTimeFromCustomInfo() throws IOException {
        try {
            GetResponse response = getClient().prepareGet("_river", riverContext.getRiverName(), ColumnRiverFlow.DOCUMENT).execute().actionGet();
            if (response != null && response.isExists()) {
                Map jdbcState = (Map) response.getSourceAsMap().get("jdbc");

                if (jdbcState != null) {
                    Number lastRunTime = (Number) jdbcState.get(ColumnRiverFlow.LAST_RUN_TIME);

                    if (lastRunTime != null) {
                        return new TimeValue(lastRunTime.longValue());
                    }
                } else {
                    throw new IOException("can't retrieve previously persisted state from _river/" + riverContext.getRiverName());
                }
            }
        } catch (IndexMissingException e) {
            logger.warn("river state missing: _river/{}/{}", riverContext.getRiverName(), "_custom");
        }

        return null;
    }

    private void writeCustomInfo(long lastRunAt) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject("jdbc")
                .field(ColumnRiverFlow.LAST_RUN_TIME, lastRunAt)
                .endObject()
                .endObject();
        getClient().prepareBulk().add(Requests.indexRequest("_river").type(riverContext.getRiverName()).id(ColumnRiverFlow.DOCUMENT)
                .source(builder.string())).execute().actionGet();
    }

    private void writeTimesToJdbcSettings(TimeValue lastRunTime, TimeValue currentTime) {
        if (riverContext == null || riverContext.getRiverSettings() == null) {
            return;
        }
        Map<String, Object> settings = riverContext.getRiverSettings();
        settings.put(ColumnRiverFlow.LAST_RUN_TIME, lastRunTime);
        settings.put(ColumnRiverFlow.CURRENT_RUN_STARTED_TIME, currentTime);
    }

}
