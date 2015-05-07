package com.flightstats.hub.group;

import com.flightstats.hub.model.Location;
import com.flightstats.hub.model.TimeQuery;
import com.flightstats.hub.util.TimeUtil;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryGenerator {

    private final static Logger logger = LoggerFactory.getLogger(QueryGenerator.class);
    private DateTime lastQueryTime;
    private String channel;

    public QueryGenerator(DateTime startTime, String channel) {
        lastQueryTime = startTime;
        this.channel = channel;
    }

    public TimeQuery getQuery(DateTime latestStableInChannel) {
        logger.trace("iterating {} last={} stable={} ", channel, lastQueryTime, latestStableInChannel);
        if (lastQueryTime.isBefore(latestStableInChannel)) {
            TimeUtil.Unit unit = getStepUnit(latestStableInChannel);
            logger.trace("query {} unit={} lastQueryTime={}", channel, unit, lastQueryTime);
            Location location = Location.ALL;
            if (unit.equals(TimeUtil.Unit.SECONDS)) {
                location = Location.CACHE;
            }
            TimeQuery query = TimeQuery.builder()
                    .channelName(channel)
                    .startTime(lastQueryTime)
                    .unit(unit)
                    .location(location)
                    .build();
            query.trace(false);
            lastQueryTime = lastQueryTime.plus(unit.getDuration()).withMillisOfSecond(0);
            if (unit == TimeUtil.Unit.HOURS) {
                lastQueryTime = lastQueryTime.withMinuteOfHour(0).withSecondOfMinute(0);
            } else if (unit == TimeUtil.Unit.MINUTES) {
                lastQueryTime = lastQueryTime.withSecondOfMinute(0);
            }
            return query;
        } else {
            return null;
        }
    }

    private TimeUtil.Unit getStepUnit(DateTime latestStableInChannel) {
        if (lastQueryTime.isBefore(latestStableInChannel.minusHours(2))) {
            return TimeUtil.Unit.HOURS;
        } else if (lastQueryTime.isBefore(latestStableInChannel.minusMinutes(2))) {
            return TimeUtil.Unit.MINUTES;
        }
        return TimeUtil.Unit.SECONDS;
    }

}
