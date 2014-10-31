package com.flightstats.hub.dao.s3;

import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.cap.Quorum;
import com.basho.riak.client.api.commands.kv.FetchValue;
import com.basho.riak.client.api.commands.kv.StoreValue;
import com.basho.riak.client.core.query.Location;
import com.basho.riak.client.core.query.Namespace;
import com.basho.riak.client.core.query.RiakObject;
import com.basho.riak.client.core.query.indexes.LongIntIndex;
import com.basho.riak.client.core.util.BinaryValue;
import com.flightstats.hub.app.HubServices;
import com.flightstats.hub.dao.ContentDao;
import com.flightstats.hub.model.ChannelConfiguration;
import com.flightstats.hub.model.Content;
import com.flightstats.hub.model.ContentKey;
import com.flightstats.hub.model.InsertedContentKey;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;

/**
 * This uses Riak for Content
 */
public class ContentDaoImpl implements ContentDao {

    private final static Logger logger = LoggerFactory.getLogger(ContentDaoImpl.class);
    private final RiakClient riakClient;

    @Inject
    public ContentDaoImpl(RiakClient riakClient) {
        this.riakClient = riakClient;
        HubServices.register(new ContentDaoInit());
    }

    @Override
    public InsertedContentKey write(String channelName, Content content, long ttlDays) {
        if (content.isNewContent()) {
            content.setContentKey(new ContentKey());
        } else {
            //todo - gfm - 10/31/14 - how should replication be handled?
        }
        ContentKey key = content.getContentKey().get();
        try {
            Namespace namespace = new Namespace("default", channelName);
            Location location = new Location(namespace, key.key());
            RiakObject riakObject = new RiakObject();
            if (content.getData().length > 0) {
                riakObject.setValue(BinaryValue.create(content.getData()));
            }
            //todo - gfm - 10/31/14 - change headers to be a map in Content

            if (content.getContentType().isPresent()) {
                riakObject.getUserMeta().put("contentType", content.getContentType().get());
            }
            if (content.getContentLanguage().isPresent()) {
                riakObject.getUserMeta().put("contentLanguage", content.getContentLanguage().get());
            }
            if (content.getUser().isPresent()) {
                riakObject.getUserMeta().put("user", content.getUser().get());
            }
            riakObject.getIndexes().getIndex(LongIntIndex.named("time")).add(key.getMillis());
            StoreValue store = new StoreValue.Builder(riakObject)
                    .withLocation(location)
                    .withOption(StoreValue.Option.W, Quorum.quorumQuorum()).build();
            riakClient.execute(store);

            return new InsertedContentKey(key, new DateTime(key.getMillis()).toDate());
        } catch (Exception e) {
            logger.warn("unable to write channel " + channelName + " key " + key, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Content read(String channelName, ContentKey key) {
        try {
            Namespace namespace = new Namespace("default", channelName);
            Location location = new Location(namespace, key.key());
            FetchValue.Response response = riakClient.execute(new FetchValue.Builder(location).build());
            RiakObject object = response.getValue(RiakObject.class);
            Content.Builder builder = Content.builder()
                    .withContentKey(key)
                    .withData(object.getValue().getValue())
                    .withMillis(key.getMillis());
            if (object.getUserMeta().containsKey("contentType")) {
                builder.withContentType(object.getUserMeta().get("contentType"));
            }
            if (object.getUserMeta().containsKey("contentLanguage")) {
                builder.withContentLanguage(object.getUserMeta().get("contentLanguage"));
            }
            if (object.getUserMeta().containsKey("user")) {
                builder.withUser(object.getUserMeta().get("user"));
            }
            return builder.build();

        } catch (Exception e) {
            logger.warn("what? " + channelName + key, e);
        }
        return null;
    }

    @Override
    public Collection<ContentKey> getKeys(String channelName, DateTime dateTime) {
        //todo - gfm - 10/31/14 - query, and allow time range
        return Collections.emptyList();
    }

    @Override
    public void initializeChannel(ChannelConfiguration config) {

        //todo - gfm - 10/31/14 - does this need to create the table in Riak?
    }

    @Override
    public Optional<ContentKey> getKey(String id) {
        return ContentKey.fromString(id);
    }

    @Override
    public void delete(String channelName) {
        //todo - gfm - 10/31/14 - delete records from Riak
    }

    private class ContentDaoInit extends AbstractIdleService {
        @Override
        protected void startUp() throws Exception {
            //todo - gfm - 10/31/14 - does this need to do anything?
        }

        @Override
        protected void shutDown() throws Exception {
        }

    }

}
