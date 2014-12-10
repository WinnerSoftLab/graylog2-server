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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.plugin.streams.StreamRule;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class StreamLookup {
    private final Map<String, List<Stream>> lookup = Maps.newHashMap();
    private final Set<String> presenceFields = Sets.newHashSet();
    private final Set<String> presenceFieldsInverted = Sets.newHashSet();
    private final Set<String> exactFields = Sets.newHashSet();
    private final Set<Stream> checkedStreams = Sets.newHashSet();

    public StreamLookup(StreamService streamService) {
        for (Stream stream : streamService.loadAllEnabled()) {
            final List<StreamRule> streamRules = stream.getStreamRules();

            // Only one rule allowed for now! The current optimization only works for streams with one rule.
            if (streamRules.size() > 1) {
                continue;
            }

            for (StreamRule rule : streamRules) {
                String key;
                switch (rule.getType()) {
                    case EXACT:
                        // TODO Support inverted exact rules!
                        if (rule.getInverted()) {
                            continue;
                        }

                        key = rule.getField() + rule.getValue();
                        exactFields.add(rule.getField());
                        addStream(lookup, checkedStreams, stream, key);
                        break;
                    case GREATER:
                        break;
                    case SMALLER:
                        break;
                    case REGEX:
                        break;
                    case PRESENCE:
                        key = rule.getField();
                        presenceFields.add(key);
                        if (rule.getInverted()) {
                            presenceFieldsInverted.add(key);
                        }
                        addStream(lookup, checkedStreams, stream, key);
                        break;
                }
            }
        }
    }

    private void addStream(Map<String, List<Stream>> lookup, Set<Stream> checkedStreams, Stream stream, String key) {
        checkedStreams.add(stream);

        if (! lookup.containsKey(key)) {
            lookup.put(key, Lists.newArrayList(stream));
        } else {
            lookup.get(key).add(stream);
        }
    }

    public Set<Stream> matches(Message msg) {
        final Set<Stream> streams = Sets.newHashSet();
        final Map<String, Object> fields = msg.getFields();

        for (final String field : presenceFields) {
            if (fields.containsKey(field) && !presenceFieldsInverted.contains(field)) {
                streams.addAll(lookup.get(field));
            }
        }

        for (final String field : presenceFieldsInverted) {
            if (! fields.containsKey(field)) {
                streams.addAll(lookup.get(field));
            }
        }

        for (final String field : exactFields) {
            final String key = field + fields.get(field);

            if (fields.containsKey(field) && lookup.containsKey(key)) {
                streams.addAll(lookup.get(key));
            }
        }

        return streams;
    }

    public Set<Stream> getCheckedStreams() {
        return ImmutableSet.copyOf(checkedStreams);
    }
}
