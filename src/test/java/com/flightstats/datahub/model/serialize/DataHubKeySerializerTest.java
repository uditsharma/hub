package com.flightstats.datahub.model.serialize;

import com.flightstats.datahub.model.DataHubKey;
import com.flightstats.datahub.util.DataHubKeyRenderer;
import org.codehaus.jackson.JsonGenerator;
import org.junit.Test;

import java.util.Date;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class DataHubKeySerializerTest {

    @Test
    public void testSerialize() throws Exception {
        DataHubKey key = new DataHubKey(new Date(90210L), (short) 4);
        DataHubKeyRenderer renderer = new DataHubKeyRenderer();

        JsonGenerator jgen = mock(JsonGenerator.class);

        DataHubKeySerializer testClass = new DataHubKeySerializer(renderer);

        testClass.serialize(key, jgen, null);
        verify(jgen).writeString("0000000005G64004");

    }
}
