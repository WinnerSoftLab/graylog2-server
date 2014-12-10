/**
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.graylog2.streams;

import com.beust.jcommander.internal.Lists;
import com.google.common.collect.ImmutableMap;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.plugin.streams.StreamRule;
import org.graylog2.plugin.streams.StreamRuleType;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

public class StreamLookupTest {
    @Mock private StreamService streamService;
    @Mock private Stream stream;
    @Mock private StreamRule streamRuleExact;
    @Mock private StreamRule streamRulePresence;
    @Mock private StreamRule streamRuleGreater;
    @Mock private Message message;

    @BeforeMethod
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(streamService.loadAllEnabled()).thenReturn(Lists.newArrayList(stream));

        when(streamRuleExact.getField()).thenReturn("field");
        when(streamRuleExact.getValue()).thenReturn("value");
        when(streamRuleExact.getInverted()).thenReturn(false);
        when(streamRuleExact.getType()).thenReturn(StreamRuleType.EXACT);

        when(streamRulePresence.getField()).thenReturn("presence-field");
        when(streamRulePresence.getValue()).thenReturn("presence-value");
        when(streamRulePresence.getInverted()).thenReturn(false);
        when(streamRulePresence.getType()).thenReturn(StreamRuleType.PRESENCE);

        when(streamRuleGreater.getType()).thenReturn(StreamRuleType.GREATER);
    }

    @Test
    public void testExactMatches() throws Exception {
        when(stream.getStreamRules()).thenReturn(Lists.newArrayList(streamRuleExact));

        // With a matching field.
        when(message.getFields()).thenReturn(ImmutableMap.<String, Object>of("field", "value"));

        StreamLookup lookup = new StreamLookup(streamService);

        assertEquals(lookup.matches(message).size(), 1);

        // With no matching field.
        when(message.getFields()).thenReturn(ImmutableMap.<String, Object>of("nofield", "value"));

        lookup = new StreamLookup(streamService);

        assertEquals(lookup.matches(message).size(), 0);

        // With no matching value.
        when(message.getFields()).thenReturn(ImmutableMap.<String, Object>of("field", "novalue"));

        lookup = new StreamLookup(streamService);

        assertEquals(lookup.matches(message).size(), 0);
    }

    @Test
    public void testInvertedExactMatches() throws Exception {
        when(stream.getStreamRules()).thenReturn(Lists.newArrayList(streamRuleExact));
        when(streamRuleExact.getInverted()).thenReturn(true);

        StreamLookup lookup = new StreamLookup(streamService);

        assertEquals(lookup.getCheckedStreams().size(), 0);
    }

    @Test
    public void testPresenceMatches() throws Exception {
        when(stream.getStreamRules()).thenReturn(Lists.newArrayList(streamRulePresence));

        // With a present field.
        when(message.getFields()).thenReturn(ImmutableMap.<String, Object>of("presence-field", "value"));

        StreamLookup lookup = new StreamLookup(streamService);

        assertEquals(lookup.matches(message).size(), 1);

        // With no present field.
        when(message.getFields()).thenReturn(ImmutableMap.<String, Object>of("nofield", "value"));

        lookup = new StreamLookup(streamService);

        assertEquals(lookup.matches(message).size(), 0);

        // With a present field and no matching value.
        when(message.getFields()).thenReturn(ImmutableMap.<String, Object>of("presence-field", "novalue"));

        lookup = new StreamLookup(streamService);

        assertEquals(lookup.matches(message).size(), 1);
    }

    @Test
    public void testInvertedPresenceMatches() throws Exception {
        when(stream.getStreamRules()).thenReturn(Lists.newArrayList(streamRulePresence));
        when(streamRulePresence.getInverted()).thenReturn(true);

        // With a present field.
        when(message.getFields()).thenReturn(ImmutableMap.<String, Object>of("presence-field", "value"));

        StreamLookup lookup = new StreamLookup(streamService);

        assertEquals(lookup.matches(message).size(), 0);

        // With no present field.
        when(message.getFields()).thenReturn(ImmutableMap.<String, Object>of("no-presence-field", "value"));

        lookup = new StreamLookup(streamService);

        assertEquals(lookup.matches(message).size(), 1);
    }


    @Test
    public void testGetCheckedStreams() throws Exception {
        when(stream.getStreamRules()).thenReturn(Lists.newArrayList(streamRuleExact));

        StreamLookup lookup = new StreamLookup(streamService);

        assertEquals(lookup.getCheckedStreams().size(), 1);

        // Currently only streams with one rule are supported!
        when(stream.getStreamRules()).thenReturn(Lists.newArrayList(streamRuleExact, streamRulePresence));

        lookup = new StreamLookup(streamService);

        assertEquals(lookup.getCheckedStreams().size(), 0);

        // Only a few rule types supported at the moment.
        when(stream.getStreamRules()).thenReturn(Lists.newArrayList(streamRuleGreater));

        lookup = new StreamLookup(streamService);

        assertEquals(lookup.getCheckedStreams().size(), 0);
    }
}