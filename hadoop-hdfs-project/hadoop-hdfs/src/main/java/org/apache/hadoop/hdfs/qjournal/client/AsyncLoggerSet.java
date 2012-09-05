/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.qjournal.client;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hdfs.qjournal.protocol.JournalNotFormattedException;
import org.apache.hadoop.hdfs.qjournal.protocol.QJournalProtocolProtos.GetJournalStateResponseProto;
import org.apache.hadoop.hdfs.qjournal.protocol.QJournalProtocolProtos.NewEpochResponseProto;
import org.apache.hadoop.hdfs.qjournal.protocol.QJournalProtocolProtos.PrepareRecoveryResponseProto;
import org.apache.hadoop.hdfs.qjournal.protocol.QJournalProtocolProtos.SegmentStateProto;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.RemoteEditLogManifest;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.jasper.compiler.JspUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

/**
 * Wrapper around a set of Loggers, taking care of fanning out
 * calls to the underlying loggers and constructing corresponding
 * {@link QuorumCall} instances.
 */
class AsyncLoggerSet {
  static final Log LOG = LogFactory.getLog(AsyncLoggerSet.class);

  private static final int NEWEPOCH_TIMEOUT_MS = 10000;
  
  private final List<AsyncLogger> loggers;
  
  private static final long INVALID_EPOCH = -1;
  private long myEpoch = INVALID_EPOCH;
  
  public AsyncLoggerSet(List<AsyncLogger> loggers) {
    this.loggers = ImmutableList.copyOf(loggers);
  }
  
  /**
   * Fence any previous writers, and obtain a unique epoch number
   * for write-access to the journal nodes.
   *
   * @param nsInfo the expected namespace information. If the remote
   * node does not match with this namespace, the request will be rejected.
   * @return the new, unique epoch number
   * @throws IOException
   */
  Map<AsyncLogger, NewEpochResponseProto> createNewUniqueEpoch(
      NamespaceInfo nsInfo) throws IOException {
    Preconditions.checkState(myEpoch == -1,
        "epoch already created: epoch=" + myEpoch);
    
    Map<AsyncLogger, GetJournalStateResponseProto> lastPromises =
      waitForWriteQuorum(getJournalState(), NEWEPOCH_TIMEOUT_MS);
    
    long maxPromised = Long.MIN_VALUE;
    for (GetJournalStateResponseProto resp : lastPromises.values()) {
      maxPromised = Math.max(maxPromised, resp.getLastPromisedEpoch());
    }
    assert maxPromised >= 0;
    
    long myEpoch = maxPromised + 1;
    Map<AsyncLogger, NewEpochResponseProto> resps =
        waitForWriteQuorum(newEpoch(nsInfo, myEpoch), NEWEPOCH_TIMEOUT_MS);
    this.myEpoch = myEpoch;
    setEpoch(myEpoch);
    return resps;
  }
  
  private void setEpoch(long e) {
    for (AsyncLogger l : loggers) {
      l.setEpoch(e);
    }
  }


  /**
   * Set the highest successfully committed txid seen by the writer.
   * This should be called after a successful write to a quorum, and is used
   * for extra sanity checks against the protocol. See HDFS-3863.
   */
  public void setCommittedTxId(long txid) {
    for (AsyncLogger logger : loggers) {
      logger.setCommittedTxId(txid);
    }
  }

  /**
   * @return the epoch number for this writer. This may only be called after
   * a successful call to {@link #createNewUniqueEpoch(NamespaceInfo)}.
   */
  long getEpoch() {
    Preconditions.checkState(myEpoch != INVALID_EPOCH,
        "No epoch created yet");
    return myEpoch;
  }

  /**
   * Close all of the underlying loggers.
   */
  void close() {
    for (AsyncLogger logger : loggers) {
      logger.close();
    }
  }
  
  void purgeLogsOlderThan(long minTxIdToKeep) {
    for (AsyncLogger logger : loggers) {
      logger.purgeLogsOlderThan(minTxIdToKeep);
    }
  }


  /**
   * Wait for a quorum of loggers to respond to the given call. If a quorum
   * can't be achieved, throws a QuorumException.
   * @param q the quorum call
   * @param timeoutMs the number of millis to wait
   * @return a map of successful results
   * @throws QuorumException if a quorum doesn't respond with success
   * @throws IOException if the thread is interrupted or times out
   */
  <V> Map<AsyncLogger, V> waitForWriteQuorum(QuorumCall<AsyncLogger, V> q,
      int timeoutMs) throws IOException {
    int majority = getMajoritySize();
    try {
      q.waitFor(
          loggers.size(), // either all respond 
          majority, // or we get a majority successes
          majority, // or we get a majority failures,
          timeoutMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted waiting for quorum results");
    } catch (TimeoutException e) {
      throw new IOException("Timed out waiting " + timeoutMs + " for write quorum");
    }
    
    if (q.countSuccesses() < majority) {
      q.rethrowException("Got too many exceptions to achieve quorum size " +
          getMajorityString());
    }
    
    return q.getResults();
  }
  
  /**
   * @return the number of nodes which are required to obtain a quorum.
   */
  int getMajoritySize() {
    return loggers.size() / 2 + 1;
  }
  
  /**
   * @return a textual description of the majority size (eg "2/3" or "3/5")
   */
  String getMajorityString() {
    return getMajoritySize() + "/" + loggers.size();
  }

  /**
   * @return the number of loggers behind this set
   */
  int size() {
    return loggers.size();
  }
  
  /**
   * Append an HTML-formatted status readout on the current
   * state of the underlying loggers.
   * @param sb the StringBuilder to append to
   */
  void appendHtmlReport(StringBuilder sb) {
    sb.append("<table class=\"storage\">");
    sb.append("<thead><tr><td>JN</td><td>Status</td></tr></thead>\n");
    for (AsyncLogger l : loggers) {
      sb.append("<tr>");
      sb.append("<td>" + JspUtil.escapeXml(l.toString()) + "</td>");
      sb.append("<td>");
      l.appendHtmlReport(sb);
      sb.append("</td></tr>\n");
    }
    sb.append("</table>");
  }

  /**
   * @return the (mutable) list of loggers, for use in tests to
   * set up spies
   */
  @VisibleForTesting
  List<AsyncLogger> getLoggersForTests() {
    return loggers;
  }
  
  ///////////////////////////////////////////////////////////////////////////
  // The rest of this file is simply boilerplate wrappers which fan-out the
  // various IPC calls to the underlying AsyncLoggers and wrap the result
  // in a QuorumCall.
  ///////////////////////////////////////////////////////////////////////////
  
  private QuorumCall<AsyncLogger, GetJournalStateResponseProto> getJournalState() {
    Map<AsyncLogger, ListenableFuture<GetJournalStateResponseProto>> calls =
        Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      calls.put(logger, logger.getJournalState());
    }
    return QuorumCall.create(calls);    
  }
  
  public QuorumCall<AsyncLogger, Boolean> isFormatted() {
    Map<AsyncLogger, ListenableFuture<Boolean>> calls = Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      final SettableFuture<Boolean> ret = SettableFuture.create();
      ListenableFuture<GetJournalStateResponseProto> jstate =
          logger.getJournalState();
      Futures.addCallback(jstate, new FutureCallback<GetJournalStateResponseProto>() {
        @Override
        public void onFailure(Throwable t) {
          if (t instanceof RemoteException) {
            t = ((RemoteException)t).unwrapRemoteException();
          }
          if (t instanceof JournalNotFormattedException) {
            ret.set(false);
          } else {
            ret.setException(t);
          }
        }

        @Override
        public void onSuccess(GetJournalStateResponseProto jstate) {
          ret.set(true);
        }
      });
      
      calls.put(logger, ret);
    }
    return QuorumCall.create(calls);
  }

  private QuorumCall<AsyncLogger,NewEpochResponseProto> newEpoch(
      NamespaceInfo nsInfo,
      long epoch) {
    Map<AsyncLogger, ListenableFuture<NewEpochResponseProto>> calls =
        Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      calls.put(logger, logger.newEpoch(epoch));
    }
    return QuorumCall.create(calls);    
  }

  public QuorumCall<AsyncLogger, Void> startLogSegment(
      long txid) {
    Map<AsyncLogger, ListenableFuture<Void>> calls = Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      calls.put(logger, logger.startLogSegment(txid));
    }
    return QuorumCall.create(calls);
  }
  
  public QuorumCall<AsyncLogger, Void> finalizeLogSegment(long firstTxId,
      long lastTxId) {
    Map<AsyncLogger, ListenableFuture<Void>> calls = Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      calls.put(logger, logger.finalizeLogSegment(firstTxId, lastTxId));
    }
    return QuorumCall.create(calls);
  }
  
  public QuorumCall<AsyncLogger, Void> sendEdits(
      long segmentTxId, long firstTxnId, int numTxns, byte[] data) {
    Map<AsyncLogger, ListenableFuture<Void>> calls = Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      ListenableFuture<Void> future = 
        logger.sendEdits(segmentTxId, firstTxnId, numTxns, data);
      calls.put(logger, future);
    }
    return QuorumCall.create(calls);
  }

  public QuorumCall<AsyncLogger, RemoteEditLogManifest>
      getEditLogManifest(long fromTxnId) {
    Map<AsyncLogger,
        ListenableFuture<RemoteEditLogManifest>> calls
        = Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      ListenableFuture<RemoteEditLogManifest> future =
          logger.getEditLogManifest(fromTxnId);
      calls.put(logger, future);
    }
    return QuorumCall.create(calls);
  }

  QuorumCall<AsyncLogger, PrepareRecoveryResponseProto>
      prepareRecovery(long segmentTxId) {
    Map<AsyncLogger,
      ListenableFuture<PrepareRecoveryResponseProto>> calls
      = Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      ListenableFuture<PrepareRecoveryResponseProto> future =
          logger.prepareRecovery(segmentTxId);
      calls.put(logger, future);
    }
    return QuorumCall.create(calls);
  }

  QuorumCall<AsyncLogger,Void>
      acceptRecovery(SegmentStateProto log, URL fromURL) {
    Map<AsyncLogger, ListenableFuture<Void>> calls
      = Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      ListenableFuture<Void> future =
          logger.acceptRecovery(log, fromURL);
      calls.put(logger, future);
    }
    return QuorumCall.create(calls);
  }

  QuorumCall<AsyncLogger,Void> format(NamespaceInfo nsInfo) {
    Map<AsyncLogger, ListenableFuture<Void>> calls =
        Maps.newHashMap();
    for (AsyncLogger logger : loggers) {
      ListenableFuture<Void> future =
          logger.format(nsInfo);
      calls.put(logger, future);
    }
    return QuorumCall.create(calls);
  }
}
