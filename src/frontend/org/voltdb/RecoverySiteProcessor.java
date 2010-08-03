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
package org.voltdb;

import java.util.HashSet;

import org.voltdb.dtxn.SiteTracker;
import org.voltdb.messaging.RecoveryMessage;
import org.voltdb.messaging.VoltMessage;

/**
 * Base class for functionality used during recovery. Derived classes implement
 * one set of functionality for the source partition and another set of functionality for the destination
 *
 */
public interface RecoverySiteProcessor {

    /**
     * doRecoveryWork loops on receiving messages. This interface is invoked
     * to handle non recovery messages.
     */
    public interface MessageHandler {
        public void handleMessage(VoltMessage message);
    }

    /**
     * This handler is invoked upon recovery completion. It is passed the txnid of the last
     * committed txn executed by the partition that was a source of recovery data. This site
     * should skip to the first txn after this ID and start executing. If this site
     * was the source it will already be there.
     *
     */
    public interface OnRecoveryCompletion {
        void complete(long txnId);
    }

    public void handleRecoveryMessage(RecoveryMessage message);
    public void handleNodeFault(HashSet<Integer> failedNodes, SiteTracker tracker);
    public void doRecoveryWork(long currentTxnId);
}