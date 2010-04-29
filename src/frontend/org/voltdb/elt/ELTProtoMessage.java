/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
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

package org.voltdb.elt;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.FastSerializer;



/**
 * Message exchanged during execution of poll/ack protocol
 */
public class ELTProtoMessage
{
    public static final int kOpen           = 1 << 0;
    public static final int kOpenResponse   = 1 << 1;
    public static final int kPoll           = 1 << 2;
    public static final int kPollResponse   = 1 << 3;
    public static final int kAck            = 1 << 4;
    public static final int kClose          = 1 << 5;
    public static final int kError          = 1 << 6;

    public boolean isOpen()         {return (m_type & kOpen) != 0;}
    public boolean isOpenResponse() {return (m_type & kOpenResponse) != 0;}
    public boolean isPoll()         {return (m_type & kPoll) != 0;}
    public boolean isPollResponse() {return (m_type & kPollResponse) != 0;}
    public boolean isAck()          {return (m_type & kAck) != 0;}
    public boolean isClose()        {return (m_type & kClose) != 0;}
    public boolean isError()        {return (m_type & kError) != 0;}


    /**
     * Called to produce an ELT protocol message from a FastDeserializer.
     * Note that this expects the length preceding value was already
     * read (probably how the buffer length was originally determined).
     * @param fds
     * @return
     * @throws IOException
     */
    public static ELTProtoMessage readExternal(FastDeserializer fds)
    throws IOException
    {
        ELTProtoMessage m = new ELTProtoMessage(0, 0);
        m.m_type = fds.readInt();
        m.m_partitionId = fds.readInt();
        m.m_tableId = fds.readInt();
        m.m_offset = fds.readLong();
        // if no data is remaining, m_data will have 0 capacity.
        m.m_data = fds.remainder();
        return m;
    }

    public ELTProtoMessage(int partitionId, int tableId) {
        m_partitionId = partitionId;
        m_tableId = tableId;
    }

    public ByteBuffer toBuffer() throws IOException {
        FastSerializer fs = new FastSerializer();
        writeToFastSerializer(fs);
        return fs.getBuffer();
    }

    /**
     * Total bytes that would be used if serialized in its current state.
     * Does not include the 4 byte length prefix!
     * @return byte count.
     */
    public int serializableBytes() {
        return FIXED_PAYLOAD_LENGTH + (m_data != null ? m_data.remaining() : 0);
    }

    public void writeToFastSerializer(FastSerializer fs) throws IOException
    {
        // write the length first. then the payload.
        fs.writeInt(serializableBytes());
        fs.writeInt(m_type);
        fs.writeInt(m_partitionId);
        fs.writeInt(m_tableId);
        fs.writeLong(m_offset);
        if (m_data != null) {
            fs.write(m_data);
            // write advances m_data's position.
            m_data.flip();
        }
    }

    public ELTProtoMessage error() {
        m_type |= kError;
        return this;
    }

    public ELTProtoMessage open() {
        m_type |= kOpen;
        return this;
    }

    public ELTProtoMessage openResponse() {
        m_type |= kOpenResponse;
        return this;
    }

    public ELTProtoMessage poll() {
        m_type |= kPoll;
        return this;
    }

    public ELTProtoMessage pollResponse(long offset, ByteBuffer bb) {
        m_type |= kPollResponse;
        m_data = bb;
        m_offset = offset;
        return this;
    }

    public ELTProtoMessage ack(long ackedOffset) {
        m_type |= kAck;
        m_offset = ackedOffset;
        return this;
    }

    public ELTProtoMessage close() {
        m_type |= kClose;
        return this;
    }

    public ByteBuffer getData() {
        return m_data;
    }

    public int getPartitionId() {
        return m_partitionId;
    }

    public int getTableId() {
        return m_tableId;
    }

    public long getAckOffset() {
        return m_offset;
    }

    @Override
    public String toString() {
        String s = "ELTProtoMessage: type(" + m_type + ") offset(" +
                m_offset + ") partitionId(" + m_partitionId +
                ") tableId(" + m_tableId +")" + " serializableBytes(" +
                serializableBytes() + ")";
        if (m_data != null) {
            s = s + " payloadBytes(" + m_data.remaining() +")";
        }
        else {
            s = s + " no payoad.";
        }
        return s;
    }

    // calculate bytes of fixed payload
    private static int FIXED_PAYLOAD_LENGTH =
        (Integer.SIZE/8 * 3) + (Long.SIZE/8 * 1);

    // bitmask of protocol actions in this message.
    int m_type = 0;

    // partition id for this ack or poll
    int m_partitionId = -1;

    // the table name for this ack or poll
    int m_tableId = -1;

    // if kAck, the offset being acked.
    // if kPollResponse, the offset of the last byte returned.
    long m_offset = -1;

    // poll payload data
    ByteBuffer m_data;
}