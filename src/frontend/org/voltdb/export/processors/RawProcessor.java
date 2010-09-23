/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.export.processors;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.voltdb.export.ExportDataProcessor;
import org.voltdb.export.ExportDataSource;
import org.voltdb.export.ExportProtoMessage;
import org.voltdb.logging.VoltLogger;
import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.FastSerializer;
import org.voltdb.messaging.MessagingException;
import org.voltdb.messaging.VoltMessage;
import org.voltdb.network.Connection;
import org.voltdb.network.InputHandler;
import org.voltdb.network.QueueMonitor;
import org.voltdb.network.VoltProtocolHandler;
import org.voltdb.utils.DBBPool;
import org.voltdb.utils.DBBPool.BBContainer;
import org.voltdb.utils.DeferredSerialization;
import org.voltdb.utils.NotImplementedException;


/**
 * A processor that provides a data block queue over a socket to
 * a remote listener without any translation of data.
 */
public class RawProcessor extends Thread implements ExportDataProcessor {

    // polling protocol states
    public final static int CONNECTED = 1;
    public final static int CLOSED = 2;

    /**
     * This logger facility is set by the Export manager and is configurable
     * via the standard VoltDB log configuration methods.
     */
    VoltLogger m_logger;

    /**
     * Work messages are queued and polled from this mailbox. All work
     * done by the processor thread is serialized through this mailbox.
     */
    private final LinkedBlockingDeque<ExportInternalMessage> m_mailbox =
        new LinkedBlockingDeque<ExportInternalMessage>();

    /**
     * Data sources, one per table per site, provide the interface to
     * poll() and ack() Export data from the execution engines. Data sources
     * are configured by the Export manager at initialization time.
     * partitionid : <tableid : datasource>.
     */
    HashMap<Integer, HashMap<Long, ExportDataSource>> m_sources =
        new HashMap<Integer, HashMap<Long, ExportDataSource>>();

    ArrayList<ExportDataSource> m_sourcesArray =
        new ArrayList<ExportDataSource>();

    /**
     * As long as m_shouldContinue is true, the service will listen for new
     * TCP/IP connections on LISTENER_PORT. At the moment, multiple client
     * connections do not maintain separate poll/ack state. Meaning, if client-1
     * acks data not yet read by client-2, that data will not be visible to
     * client-2. Using multiple clients is heavily advised against.
     */
    final AtomicBoolean m_shouldContinue = new AtomicBoolean(true);

    /**
     * Set of accepted client connections. MUST synchronize on m_connections
     * to read or modify this collection. This data member is accessed concurrently
     * by AcceptorThread and the processor thread.
     */
    final ArrayDeque<Connection> m_connections = new ArrayDeque<Connection>();

    /**
     * State for an individual Export protocol connection. This could probably be
     * integrated with the PollingProtocolHandler but that object is pretty
     * exclusively called by the network threadpool. Separating this makes it
     * easier to think about concurrency.
     */
    class ProtoStateBlock {
        ProtoStateBlock(Connection c) {
            m_c = c;
            m_state = RawProcessor.CLOSED;
        }

        /**
         * This is the only valid method to transition state to closed
         * @throws MessagingException
         */
        void closeConnection() {
            m_state = RawProcessor.CLOSED;
            for (ExportDataSource ds : m_sourcesArray) {
                ExportProtoMessage m =
                    new ExportProtoMessage(ds.getPartitionId(), ds.getTableId()).close();
                try {
                    ds.exportAction(new ExportInternalMessage(this, m));
                } catch (Exception e) {
                    //
                    throw new RuntimeException(e);
                }
            }
        }

        /**
         * Produce a protocol error.
         * @param m message that caused the error
         * @param string error message
         * @throws MessagingException
         */
        void protocolError(ExportProtoMessage m, String string)
        {
            if (m_logger != null) {
                m_logger.error("Closing Export connection with error: " + string);
            }
            closeConnection();

            final ExportProtoMessage r =
                new ExportProtoMessage(m.getPartitionId(), m.getTableId());
            r.error();

            m_c.writeStream().enqueue(
                new DeferredSerialization() {
                    @Override
                    public BBContainer serialize(DBBPool p) throws IOException {
                        // Must account for length prefix - thus "+4",
                        FastSerializer fs = new FastSerializer(p, r.serializableBytes() + 4);
                        r.writeToFastSerializer(fs);
                        return fs.getBBContainer();
                    }
                    @Override
                    public void cancel() {
                    }
                });
        }

        void event(final ExportProtoMessage m)
        {
            if (m.isError()) {
                protocolError(m, "Internal error message. May indicate that an invalid ack offset was requested.");
                return;
            }

            else if (m.isOpenResponse()) {
                protocolError(m, "Server must not receive open response message.");
                return;
            }

            else if (m.isOpen()) {
                if (m_state != RawProcessor.CLOSED) {
                    protocolError(m, "Client must not open an already opened connection.");
                    return;
                }
                if (m.isClose() || m.isPoll() || m.isAck()) {
                    protocolError(m, "Invalid combination of open with close, poll or ack.");
                    return;
                }
                m_state = RawProcessor.CONNECTED;

                // Respond by advertising the full data source set.
                FastSerializer fs = new FastSerializer();
                try {
                    // serialize an array of DataSources
                    fs.writeInt(m_sourcesArray.size());
                    for (ExportDataSource src : m_sourcesArray) {
                        src.writeAdvertisementTo(fs);
                    }
                }
                catch (IOException e) {
                    protocolError(m, "Error producing open response advertisement.");
                    return;
                }

                final ExportProtoMessage r = new ExportProtoMessage(-1, -1);
                r.openResponse(fs.getBuffer());
                m_c.writeStream().enqueue(
                    new DeferredSerialization() {
                        @Override
                        public BBContainer serialize(DBBPool p) throws IOException {
                            // Must account for length prefix - thus "+4",
                            FastSerializer fs = new FastSerializer(p, r.serializableBytes() + 4);
                            r.writeToFastSerializer(fs);
                            return fs.getBBContainer();
                        }
                        @Override
                        public void cancel() {
                        }
                    });
                return;
            }

            else if (m.isPoll() || m.isAck()) {
                if (m_state != RawProcessor.CONNECTED) {
                    protocolError(m, "Must not poll or ack a closed connection");
                    return;
                }
                ExportDataSource source =
                    RawProcessor.this.getDataSourceFor(m.getPartitionId(), m.getTableId());
                if (source == null) {
                    protocolError(m, "No Export data source exists for partition(" +
                                  m.getPartitionId() + ") and table(" +
                                  m.getTableId() + ") pair.");
                    return;
                }
                try {
                    source.exportAction(new ExportInternalMessage(this, m));
                    return;
                } catch (MessagingException e) {
                    protocolError(m, e.getMessage());
                    return;
                }
            }

            else if (m.isClose()) {
                // no  response to CLOSE
                closeConnection();
                return;
            }

            else if (m.isPollResponse()) {
                // Forward this response to the IO system. It originated at an
                // ExecutionSite that processed an exportAction.
                m_c.writeStream().enqueue(
                    new DeferredSerialization() {
                        @Override
                        public BBContainer serialize(DBBPool p) throws IOException {
                            // remember +4 longsword of length prefixing.
                            FastSerializer fs = new FastSerializer(p, m.serializableBytes() + 4);
                            m.writeToFastSerializer(fs);
                            return fs.getBBContainer();
                        }
                        @Override
                        public void cancel() {
                        }
                    });
                return;
            }
        }

        final Connection m_c;
        int m_state;
    }

    /**
     * Silly pair to couple a protostate block with a message for the
     * m_mailbox queue. Make this a VoltMessage, though it is sort of
     * meaningless, to satisfy ExecutionSite mailbox requirements.
     */
    public static class ExportInternalMessage extends VoltMessage {
        public final ProtoStateBlock m_sb;
        public final ExportProtoMessage m_m;

        public ExportInternalMessage(ProtoStateBlock sb, ExportProtoMessage m) {
            m_sb = sb;
            m_m = m;
        }

        @Override
        protected void flattenToBuffer(DBBPool pool) {
            throw new NotImplementedException("Invalid serialization request.");
        }

        @Override
        protected void initFromBuffer() {
            throw new NotImplementedException("Invalid serialization request.");
        }
    }

    /**
     * The network read handler for the raw processor network stream.
     * Must extend VoltPrococolHandler as NIOReadStream has only
     * package private methods. The handler is very simple; it uses
     * the base class to read length prefixed messages from the network
     * and pushes those messages into the processor's mailbox queue.
     */
    private class ExportInputHandler extends VoltProtocolHandler
    {
        /**
         * Called by VoltNetwork after the connection object is constructed
         * and before the channel is registered to the selector.
         */
        @Override
        public void starting(Connection c) {
            m_sb = new ProtoStateBlock(c);
        }

        /**
         * Called by VoltNetwork when the port is unregistered.
         */
        @Override
        public void stopping(Connection c) {
            m_sb.closeConnection();
        }

        @Override
        public int getExpectedOutgoingMessageSize() {
            // roughly 2MB plus the message metadata
            return (1024 * 1024 * 2) + 128;
        }

        @Override
        public int getMaxRead() {
            // only receiving poll requests. 8k should be plenty
            return 1024 * 8;
        }

        @Override
        public void handleMessage(final ByteBuffer message, final Connection c) {
            try {
                FastDeserializer fds = new FastDeserializer(message);
                ExportProtoMessage m = ExportProtoMessage.readExternal(fds);
                m_mailbox.add(new ExportInternalMessage(m_sb, m));
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Runnable offBackPressure() {
            return new Runnable() {
                @Override
                public void run() {
                    m_sb.m_c.enableReadSelection();
                }
            };
        }

        @Override
        public Runnable onBackPressure() {
            return null;
        }

        @Override
        public QueueMonitor writestreamMonitor() {
            return null;
        }

        private ProtoStateBlock m_sb;
    }


    //
    //
    // The actual RawProcessor implementation
    //
    //

    public RawProcessor() {
        m_logger = null;
    }

    ExportDataSource getDataSourceFor(int partitionId, long tableId) {
        HashMap<Long, ExportDataSource> partmap = m_sources.get(partitionId);
        if (partmap == null) {
            return null;
        }
        ExportDataSource source = partmap.get(tableId);
        return source;
    }


    @Override
    public void addDataSource(ExportDataSource dataSource) {
        int partid = dataSource.getPartitionId();
        long tableid = dataSource.getTableId();

        m_sourcesArray.add(dataSource);
        HashMap<Long, ExportDataSource> partmap = m_sources.get(partid);
        if (partmap == null) {
            partmap = new HashMap<Long, ExportDataSource>();
            m_sources.put(partid, partmap);
        }
        assert(partmap.get(tableid) == null);
        partmap.put(tableid, dataSource);
    }

    @Override
    public void readyForData() {
        m_logger.info("Processor ready for data.");
        this.start();
    }

    @Override
    public void addLogger(VoltLogger logger) {
        m_logger = logger;
    }

    @Override
    public void queueMessage(ExportInternalMessage m) {
        m_mailbox.add(m);
    }

    // this run loop can almost be eliminated.  the InputHandler can
    // call the sb.event() function directly. event() never blocks.
    // just need a way for execution sites to respond with poll
    // responses. Would be really great if we had an interface to
    // schedule a non-network event against an input handler.
    @Override
    public void run() {
        ExportInternalMessage p;
        while (m_shouldContinue.get() == true) {
            try {
                p = m_mailbox.poll(5000, TimeUnit.MILLISECONDS);
                if (p != null) {
                    p.m_sb.event(p.m_m);
                }
            }
            catch (InterruptedException e) {
                // acceptable. just re-loop.
            }
        }
    }

    @Override
    public InputHandler createInputHandler(String service) {
        if (service.equalsIgnoreCase("export")) {
            return new ExportInputHandler();
        }
        return null;
    }

    @Override
    public void shutdown() {
        m_shouldContinue.set(false);
        this.interrupt();
        try {
            this.join();
        }
        catch (InterruptedException e) {
        }
    }

    @Override
    public boolean isConnectorForService(String service) {
        if (service.equalsIgnoreCase("export")) {
            return true;
        }
        return false;
    }

}