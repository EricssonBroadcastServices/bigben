package com.walmartlabs.components.scheduler.core.hz;

import com.hazelcast.core.IMap;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.walmart.gmp.ingestion.platform.framework.core.AbstractIDSGMPEntryProcessor;
import com.walmart.gmp.ingestion.platform.framework.core.Hz;
import com.walmart.gmp.ingestion.platform.framework.data.core.DataManager;
import com.walmart.services.nosql.data.CqlDAO;
import com.walmartlabs.components.scheduler.model.Bucket;
import com.walmartlabs.components.scheduler.model.BucketDO;
import com.walmartlabs.components.scheduler.model.Event;
import com.walmartlabs.components.scheduler.model.EventLookup;
import com.walmartlabs.components.scheduler.model.EventLookupDO.EventLookupKey;
import com.walmartlabs.components.scheduler.model.EventDO.EventKey;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import javax.cache.processor.EntryProcessorException;
import java.io.IOException;
import java.util.Map;

import static com.walmart.gmp.ingestion.platform.framework.core.Props.PROPS;
import static com.walmart.gmp.ingestion.platform.framework.data.core.AbstractDAO.implClass;
import static com.walmart.gmp.ingestion.platform.framework.data.core.EntityVersion.V1;
import static com.walmartlabs.components.scheduler.core.hz.ScheduleScanner.BUCKET_CACHE;
import static com.walmartlabs.components.scheduler.core.hz.ObjectFactory.OBJECT_ID.EVENT_RECEIVER_ADD_EVENT;
import static com.walmartlabs.components.scheduler.core.hz.ObjectFactory.SCHEDULER_FACTORY_ID;
import static com.walmartlabs.components.scheduler.model.Bucket.BucketStatus.UN_PROCESSED;
import static com.walmartlabs.components.scheduler.utils.TimeUtils.*;
import static java.lang.String.format;

/**
 * Created by smalik3 on 3/23/16
 */
public class HzEventReceiver {

    private static final Logger L = Logger.getLogger(HzEventReceiver.class);

    @Autowired
    private DataManager<EventKey, Event> dataManager;

    @Autowired
    private Hz hz;

    @Autowired
    private DataManager<EventLookupKey, EventLookup> lookupDataManager;

    public void addEvent(Event entity) {
        try {
            entity.id().setOffsetTime(toHour(entity.id().getEventTime()));
            L.debug(format("%s, add-event: bucket-table: insert, %s", entity.id(), entity));
            final IMap<Long, Bucket> cache = hz.hz().getMap(BUCKET_CACHE);
            final long bucketOffset = toOffset(toHour(entity.id().getEventTime()));
            L.debug(format("%s, event-time: %d -> bucket-offset: %d", entity.id(), entity.id().getEventTime(), bucketOffset));
            L.debug(format("%s, adjusting counts", entity.id()));
            final Long shardIndex = (Long) cache.executeOnKey(bucketOffset, new CountIncrementer(entity.id().toString()));
            final EventKey eventKey = EventKey.of(toAbsolute(bucketOffset), shardIndex.intValue(), entity.id().getEventTime(), entity.id().getEventId());
            L.debug(format("%s, add-event: event-table: insert", eventKey));
            final Event e = DataManager.entity(Event.class, eventKey);
            e.setState(UN_PROCESSED.name());
            dataManager.insert(e);
            L.debug(format("%s, add-event: event-table: insert: successful", eventKey));
            L.debug(format("%s, add-event: event-lookup-table: insert", eventKey));
            final EventLookup lookupEntity = DataManager.entity(EventLookup.class, EventLookupKey.of(eventKey.getEventTime(), eventKey.getEventId()));
            lookupEntity.setOffset(eventKey.getOffsetTime());
            lookupEntity.setShard(eventKey.getShard());
            lookupDataManager.insert(lookupEntity);
            L.debug(format("%s, add-event: event-lookup-table: insert: successful", eventKey));
        } catch (Exception e1) {
            e1.printStackTrace();
        }
    }

    public static class CountIncrementer extends AbstractIDSGMPEntryProcessor<Long, Bucket> {

        private String eventKey;

        public CountIncrementer(String eventKey) {
            this.eventKey = eventKey;
        }

        public CountIncrementer() {
        }

        @Override
        public Long process(Map.Entry<Long, Bucket> entry) throws EntryProcessorException {
            final Bucket e = entry.getValue() == null ? new BucketDO() : entry.getValue();
            L.debug(format("%s, bucket-offset: %d, old-count: %d, new-count: %d ", eventKey, entry.getKey(), e.getCount(), e.getCount() + 1));
            e.setCount(e.getCount() + 1);
            entry.setValue(e);
            L.debug(format("%s, add-event: bucket-table: update: successful", eventKey));
            final int shardSize = PROPS.getInteger("event.shard.size", 1000);
            return e.getCount() / shardSize;
        }

        @Override
        public int getFactoryId() {
            return SCHEDULER_FACTORY_ID;
        }

        @Override
        public int getId() {
            return EVENT_RECEIVER_ADD_EVENT.ordinal();
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeUTF(eventKey);
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            eventKey = in.readUTF();
        }
    }

    public void removeEvent(Event entity) {
        @SuppressWarnings("unchecked")
        final CqlDAO<EventLookupKey, EventLookup> cqlDAO = (CqlDAO<EventLookupKey, EventLookup>) dataManager.getPrimaryDAO(V1).unwrap();
        final EventLookupKey id = EventLookupKey.of(entity.id().getEventTime(), entity.id().getEventId());

        final EventLookup e = lookupDataManager.get(id);
        L.debug(format("%s, delete-event: event-lookup-table", entity.id()));
        cqlDAO.removeById(implClass(V1, EventLookupKey.class), id);

        if (e != null) {
            @SuppressWarnings("unchecked")
            final CqlDAO<EventKey, Event> cqlDAO1 = (CqlDAO<EventKey, Event>) dataManager.getPrimaryDAO(V1).unwrap();
            L.debug(format("%s, delete-event: event-table", entity.id()));
            cqlDAO1.removeById(implClass(V1, EventKey.class), e.id());
        }
        L.debug(format("%s, delete-event: successful", entity.id()));
    }
}
