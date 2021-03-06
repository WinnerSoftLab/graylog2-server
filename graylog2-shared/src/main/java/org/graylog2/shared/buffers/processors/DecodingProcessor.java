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

package org.graylog2.shared.buffers.processors;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.common.net.InetAddresses;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.lmax.disruptor.EventHandler;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.ResolvableInetSocketAddress;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.buffers.MessageEvent;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.codecs.Codec;
import org.graylog2.plugin.journal.RawMessage;
import org.graylog2.shared.inputs.InputRegistry;
import org.graylog2.shared.inputs.PersistedInputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

public class DecodingProcessor implements EventHandler<MessageEvent> {
    private static final Logger log = LoggerFactory.getLogger(DecodingProcessor.class);

    private final LoadingCache<String, MessageInput> inputCache;
    private final Timer decodeTime;

    public interface Factory {
        public DecodingProcessor create(@Assisted("decodeTime") Timer decodeTime, @Assisted("parseTime") Timer parseTime);
    }

    private final Map<String, Codec.Factory<? extends Codec>> codecFactory;
    private final ServerStatus serverStatus;
    private final MetricRegistry metricRegistry;
    private final PersistedInputs persistedInputs;
    private final Timer parseTime;

    @AssistedInject
    public DecodingProcessor(Map<String, Codec.Factory<? extends Codec>> codecFactory,
                             final InputRegistry inputRegistry,
                             final ServerStatus serverStatus,
                             final MetricRegistry metricRegistry,
                             final PersistedInputs persistedInputs,
                             @Assisted("decodeTime") Timer decodeTime,
                             @Assisted("parseTime") Timer parseTime) {
        this.codecFactory = codecFactory;
        this.serverStatus = serverStatus;
        this.metricRegistry = metricRegistry;
        this.persistedInputs = persistedInputs;

        // these metrics are global to all processors, thus they are passed in directly to avoid relying on the class name
        this.parseTime = parseTime;
        this.decodeTime = decodeTime;

        // Use cache here to avoid looking up the inputs in the InputRegistry for every message.
        // TODO Check if there is a better way to do this!
        this.inputCache = CacheBuilder.newBuilder()
                .expireAfterAccess(1, TimeUnit.SECONDS)
                .build(new CacheLoader<String, MessageInput>() {
                    @Override
                    public MessageInput load(String inputId) throws Exception {
                        // TODO this creates a completely new MessageInput instance every time.
                        return persistedInputs.get(inputId);
                    }
                });
    }

    @Override
    public void onEvent(MessageEvent event, long sequence, boolean endOfBatch) throws Exception {
        final Timer.Context context = decodeTime.time();
        try {
            // always set the result of processMessage, even if it is null, to avoid later stages to process old messages.
            // basically this will make sure old messages are cleared out early.
            event.setMessage(processMessage(event.getRaw()));
        } finally {
            if (event.getMessage() != null) {
                event.getMessage().recordTiming(serverStatus, "decode", context.stop());
            }
            // aid garbage collection to collect the raw message early (to avoid promoting it to later generations).
            event.clearRaw();
        }
    }

    private Message processMessage(RawMessage raw) throws ExecutionException {
        if (raw == null) {
            log.warn("Ignoring null message");
            return null;
        }

        final Codec codec = codecFactory.get(raw.getCodecName()).create(raw.getCodecConfig());

        // for backwards compatibility: the last source node should contain the input we use.
        // this means that extractors etc defined on the prior inputs are silently ignored.
        // TODO fix the above
        String inputIdOnCurrentNode;
        try {
            // .inputId checked during raw message decode!
            inputIdOnCurrentNode = Iterables.getLast(raw.getSourceNodes()).inputId;
        } catch (NoSuchElementException e) {
            inputIdOnCurrentNode = null;
        }
        final String baseMetricName = name(codec.getClass(), inputIdOnCurrentNode);

        final Message message;

        // TODO Create parse times per codec as well. (add some more metrics too)
        final Timer.Context decodeTimeCtx = parseTime.time();
        final long decodeTime;
        try {
            message = codec.decode(raw);
            if (message != null) {
                message.setJournalOffset(raw.getJournalOffset());
            }
        } catch (RuntimeException e) {
            metricRegistry.meter(name(baseMetricName, "failures")).mark();
            throw e;
        } finally {
            decodeTime = decodeTimeCtx.stop();
        }

        if (message == null) {
            metricRegistry.meter(name(baseMetricName, "failures")).mark();
            return null;
        }
        if (!message.isComplete()) {
            metricRegistry.meter(name(baseMetricName, "incomplete")).mark();
            if (log.isDebugEnabled()) {
                log.debug("Dropping incomplete message. Parsed fields: [{}]", message.getFields());
            }
            return null;
        }

        message.recordTiming(serverStatus, "parse", decodeTime);

        for (final RawMessage.SourceNode node : raw.getSourceNodes()) {
            switch (node.type) {
                case SERVER:
                    // Currently only one of each type supported at the moment.
                    if (message.getField("gl2_source_input") != null) {
                        throw new IllegalStateException("Multiple server nodes");
                    }
                    message.addField("gl2_source_input", node.inputId);
                    message.addField("gl2_source_node", node.nodeId);
                    break;
                case RADIO:
                    // Currently only one of each type supported at the moment.
                    if (message.getField("gl2_source_radio_input") != null) {
                        throw new IllegalStateException("Multiple radio nodes");
                    }
                    message.addField("gl2_source_radio_input", node.inputId);
                    message.addField("gl2_source_radio", node.nodeId);
                    break;
            }
        }

        if (inputIdOnCurrentNode != null) {
            try {
                message.setSourceInput(inputCache.get(inputIdOnCurrentNode));
            } catch (RuntimeException e) {
                log.warn("Unable to find input with id " + inputIdOnCurrentNode + ", not setting input id in this message.", e);
            }
        }

        final ResolvableInetSocketAddress remoteAddress = raw.getRemoteAddress();
        if (remoteAddress != null) {
            message.addField("gl2_remote_ip", InetAddresses.toAddrString(remoteAddress.getAddress()));
            if (remoteAddress.getPort() > 0) {
                message.addField("gl2_remote_port", remoteAddress.getPort());
            }
            if (remoteAddress.isReverseLookedUp()) { // avoid reverse lookup if the hostname is available
                message.addField("gl2_remote_hostname", remoteAddress.getHostName());
            }
        }

        metricRegistry.meter(name(baseMetricName, "processedMessages")).mark();
        return message;
    }
}
