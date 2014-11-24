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

package org.graylog2.outputs;

import com.codahale.metrics.CsvReporter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.plugin.outputs.MessageOutputConfigurationException;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.codahale.metrics.MetricRegistry.name;

public class MeasurementOutput implements MessageOutput {
    private static final List<String> SKIPPED_METRIC_PREFIXES = ImmutableList.of("org.graylog2.rest.resources");

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Meter messagesWritten;
    private final CsvReporter csvReporter;

    @Inject
    public MeasurementOutput(final MetricRegistry metricRegistry) {
        this.messagesWritten = metricRegistry.meter(name(this.getClass(), "messagesWritten"));

        csvReporter = CsvReporter.forRegistry(metricRegistry)
                .formatFor(Locale.US)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .filter(new CsvMetricFilter(SKIPPED_METRIC_PREFIXES))
                .build(new File("measurements"));

        csvReporter.start(1, TimeUnit.SECONDS);

        isRunning.set(true);
    }

    @Override
    public void initialize(Configuration config) throws MessageOutputConfigurationException {
    }

    @Override
    public void stop() {
        csvReporter.stop();
        isRunning.set(false);
    }

    @Override
    public boolean isRunning() {
        return isRunning.get();
    }

    @Override
    public void write(Message message) throws Exception {
        messagesWritten.mark();
    }

    @Override
    public void write(List<Message> messages) throws Exception {
        messagesWritten.mark(messages.size());
    }

    @Override
    public ConfigurationRequest getRequestedConfiguration() {
        return new ConfigurationRequest();
    }

    @Override
    public String getName() {
        return "Measurement output";
    }

    @Override
    public String getHumanName() {
        return "Output that measures message rates";
    }

    @Override
    public String getLinkToDocs() {
        return null;
    }

    private class CsvMetricFilter implements MetricFilter {
        private final List<String> prefixes;

        public CsvMetricFilter(List<String> prefixes) {
            this.prefixes = prefixes;
        }

        @Override
        public boolean matches(String name, Metric metric) {
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    return false;
                }
            }

            return true;
        }
    }
}
