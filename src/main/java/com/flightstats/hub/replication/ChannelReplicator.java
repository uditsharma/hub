package com.flightstats.hub.replication;

import com.flightstats.hub.cluster.CuratorLeader;
import com.flightstats.hub.cluster.Leader;
import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.model.ChannelConfiguration;
import com.flightstats.hub.model.Content;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChannelReplicator implements Leader {
    private static final Logger logger = LoggerFactory.getLogger(ChannelReplicator.class);

    private final ChannelService channelService;
    private final ChannelUtils channelUtils;
    private final SequenceFinder sequenceFinder;
    private final CuratorFramework curator;
    private final SequenceIteratorFactory sequenceIteratorFactory;
    private ChannelConfiguration configuration;

    private Channel channel;
    private SequenceIterator iterator;
    private long historicalDays;
    private boolean valid = false;
    private String message = "";
    private CuratorLeader curatorLeader;

    @Inject
    public ChannelReplicator(ChannelService channelService, ChannelUtils channelUtils,
                             SequenceIteratorFactory sequenceIteratorFactory,
                             SequenceFinder sequenceFinder, CuratorFramework curator) {
        this.channelService = channelService;
        this.sequenceIteratorFactory = sequenceIteratorFactory;
        this.channelUtils = channelUtils;
        this.sequenceFinder = sequenceFinder;
        this.curator = curator;
    }

    public void setHistoricalDays(long historicalDays) {
        this.historicalDays = historicalDays;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public boolean isValid() {
        return valid;
    }

    public String getMessage() {
        return message;
    }

    public boolean tryLeadership() {
        logger.debug("starting run " + channel);
        valid = validateRemoteChannel();
        if (!valid) {
            return false;
        }
        curatorLeader = new CuratorLeader(getLeaderPath(channel.getName()), this, curator);
        curatorLeader.start();
        return true;
    }

    private String getLeaderPath(String channelName) {
        return "/ChannelReplicator/" + channelName;
    }

    @Override
    public void takeLeadership(AtomicBoolean hasLeadership) {
        try {
            Thread.currentThread().setName("ChannelReplicator-" + channel.getUrl());
            logger.info("takeLeadership");
            valid = validateRemoteChannel();
            if (!valid) {
                exit();
                return;
            }
            createLocalChannel();
            replicate(hasLeadership);
        } finally {
            Thread.currentThread().setName("Empty");
        }
    }

    public void exit() {
        closeIterator();
        try {
            if (curatorLeader != null) {
                curatorLeader.close();
            }
        } catch (Exception e) {
            logger.warn("unable to close curatorLeader", e);
        }
    }

    private void closeIterator() {
        try {
            if (iterator != null) {
                iterator.exit();
            }
        } catch (Exception e) {
            logger.warn("unable to close iterator", e);
        }
    }

    public void delete(String channelName) {
        exit();
    }

    @VisibleForTesting
    boolean validateRemoteChannel() {
        try {
            Optional<ChannelConfiguration> optionalConfig = channelUtils.getConfiguration(channel.getUrl());
            if (!optionalConfig.isPresent()) {
                message = "remote channel missing for " + channel.getUrl();
                logger.warn(message);
                return false;
            }
            configuration = optionalConfig.get();
            channel.setConfiguration(configuration);
            logger.debug("configuration " + configuration);
            return true;
        } catch (IOException e) {
            message = "IOException " + channel.getUrl() + " " + e.getMessage();
            logger.warn(message);
            return false;
        }
    }

    @VisibleForTesting
    void createLocalChannel() {
        if (!channelService.channelExists(configuration.getName())) {
            logger.info("creating channel for " + channel.getUrl());
            channelService.createChannel(configuration);
        }
    }

    private void replicate(AtomicBoolean hasLeadership) {
        long sequence = getLastUpdated();
        if (sequence == ChannelUtils.NOT_FOUND) {
            return;
        }
        logger.info("starting " + channel.getUrl() + " migration at " + sequence);
        iterator = sequenceIteratorFactory.create(sequence, channel);
        try {
            while (iterator.hasNext() && hasLeadership.get()) {
                Optional<Content> optionalContent = iterator.next();
                if (optionalContent.isPresent()) {
                    channelService.insert(channel.getName(), optionalContent.get());
                } else {
                    logger.warn("missing content for " + channel.getUrl());
                }
            }

        } finally {
            logger.info("stopping " + channel.getUrl() + " migration ");
            closeIterator();
        }
    }

    public long getLastUpdated() {
        //todo - gfm - 10/28/14 -
        /*Optional<ContentKey> lastUpdatedKey = channelService.findLastUpdatedKey(channel.getName());
        if (lastUpdatedKey.isPresent()) {
            if (lastUpdatedKey.get().getSequence() == ContentKey.START_VALUE) {
                return sequenceFinder.searchForLastUpdated(channel, ContentKey.START_VALUE, historicalDays, TimeUnit.DAYS);
            }
            return sequenceFinder.searchForLastUpdated(channel, lastUpdatedKey.get().getSequence(), historicalDays + 1, TimeUnit.DAYS);
        }
        logger.warn("problem getting starting sequence " + channel.getUrl());*/
        return ChannelUtils.NOT_FOUND;
    }

    public boolean isConnected() {
        if (null == iterator) {
            return false;
        }
        return iterator.isConnected();
    }

}