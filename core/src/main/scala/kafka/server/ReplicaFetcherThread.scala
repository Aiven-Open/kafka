/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import kafka.log.remote.RemoteIndexCache.TmpFileSuffix
import kafka.log.{LeaderOffsetIncremented, LogAppendInfo, UnifiedLog}
import kafka.server.checkpoints.LeaderEpochCheckpointFile
import kafka.server.epoch.EpochEntry
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.record.MemoryRecords
import org.apache.kafka.common.requests._
import org.apache.kafka.server.common.MetadataVersion
import org.apache.kafka.server.log.remote.storage.{RemoteLogSegmentMetadata, RemoteStorageException}
import org.apache.kafka.server.log.remote.storage.RemoteStorageManager.IndexType

import java.io.{File, InputStream}
import java.nio.file.{Files, StandardCopyOption}
import java.util.Optional

class ReplicaFetcherThread(name: String,
                           leader: LeaderEndPoint,
                           brokerConfig: KafkaConfig,
                           failedPartitions: FailedPartitions,
                           replicaMgr: ReplicaManager,
                           quota: ReplicaQuota,
                           logPrefix: String,
                           metadataVersionSupplier: () => MetadataVersion)
  extends AbstractFetcherThread(name = name,
                                clientId = name,
                                leader = leader,
                                failedPartitions,
                                fetchBackOffMs = brokerConfig.replicaFetchBackoffMs,
                                isInterruptible = false,
                                replicaMgr.brokerTopicStats) {

  this.logIdent = logPrefix

  override protected val isOffsetForLeaderEpochSupported: Boolean = metadataVersionSupplier().isOffsetForLeaderEpochSupported

  override protected def latestEpoch(topicPartition: TopicPartition): Option[Int] = {
    replicaMgr.localLogOrException(topicPartition).latestEpoch
  }

  override protected def logStartOffset(topicPartition: TopicPartition): Long = {
    replicaMgr.localLogOrException(topicPartition).logStartOffset
  }

  override protected def logEndOffset(topicPartition: TopicPartition): Long = {
    replicaMgr.localLogOrException(topicPartition).logEndOffset
  }

  override protected def endOffsetForEpoch(topicPartition: TopicPartition, epoch: Int): Option[OffsetAndEpoch] = {
    replicaMgr.localLogOrException(topicPartition).endOffsetForEpoch(epoch)
  }

  override def initiateShutdown(): Boolean = {
    val justShutdown = super.initiateShutdown()
    if (justShutdown) {
      // This is thread-safe, so we don't expect any exceptions, but catch and log any errors
      // to avoid failing the caller, especially during shutdown. We will attempt to close
      // leaderEndpoint after the thread terminates.
      try {
        leader.initiateClose()
      } catch {
        case t: Throwable =>
          error(s"Failed to initiate shutdown of $leader after initiating replica fetcher thread shutdown", t)
      }
    }
    justShutdown
  }

  override def awaitShutdown(): Unit = {
    super.awaitShutdown()
    // We don't expect any exceptions here, but catch and log any errors to avoid failing the caller,
    // especially during shutdown. It is safe to catch the exception here without causing correctness
    // issue because we are going to shutdown the thread and will not re-use the leaderEndpoint anyway.
    try {
      leader.close()
    } catch {
      case t: Throwable =>
        error(s"Failed to close $leader after shutting down replica fetcher thread", t)
    }
  }

  // process fetched data
  override def processPartitionData(topicPartition: TopicPartition,
                                    fetchOffset: Long,
                                    partitionData: FetchData): Option[LogAppendInfo] = {
    val logTrace = isTraceEnabled
    val partition = replicaMgr.getPartitionOrException(topicPartition)
    val log = partition.localLogOrException
    val records = toMemoryRecords(FetchResponse.recordsOrFail(partitionData))

    maybeWarnIfOversizedRecords(records, topicPartition)

    if (fetchOffset != log.logEndOffset)
      throw new IllegalStateException("Offset mismatch for partition %s: fetched offset = %d, log end offset = %d.".format(
        topicPartition, fetchOffset, log.logEndOffset))

    if (logTrace)
      trace("Follower has replica log end offset %d for partition %s. Received %d messages and leader hw %d"
        .format(log.logEndOffset, topicPartition, records.sizeInBytes, partitionData.highWatermark))

    // Append the leader's messages to the log
    val logAppendInfo = partition.appendRecordsToFollowerOrFutureReplica(records, isFuture = false)

    if (logTrace)
      trace("Follower has replica log end offset %d after appending %d bytes of messages for partition %s"
        .format(log.logEndOffset, records.sizeInBytes, topicPartition))
    val leaderLogStartOffset = partitionData.logStartOffset

    // For the follower replica, we do not need to keep its segment base offset and physical position.
    // These values will be computed upon becoming leader or handling a preferred read replica fetch.
    val followerHighWatermark = log.updateHighWatermark(partitionData.highWatermark)
    log.maybeIncrementLogStartOffset(leaderLogStartOffset, LeaderOffsetIncremented)
    if (logTrace)
      trace(s"Follower set replica high watermark for partition $topicPartition to $followerHighWatermark")

    // Traffic from both in-sync and out of sync replicas are accounted for in replication quota to ensure total replication
    // traffic doesn't exceed quota.
    if (quota.isThrottled(topicPartition))
      quota.record(records.sizeInBytes)

    if (partition.isReassigning && partition.isAddingLocalReplica)
      brokerTopicStats.updateReassignmentBytesIn(records.sizeInBytes)

    brokerTopicStats.updateReplicationBytesIn(records.sizeInBytes)

    logAppendInfo
  }

  def maybeWarnIfOversizedRecords(records: MemoryRecords, topicPartition: TopicPartition): Unit = {
    // oversized messages don't cause replication to fail from fetch request version 3 (KIP-74)
    if (metadataVersionSupplier().fetchRequestVersion <= 2 && records.sizeInBytes > 0 && records.validBytes <= 0)
      error(s"Replication is failing due to a message that is greater than replica.fetch.max.bytes for partition $topicPartition. " +
        "This generally occurs when the max.message.bytes has been overridden to exceed this value and a suitably large " +
        "message has also been sent. To fix this problem increase replica.fetch.max.bytes in your broker config to be " +
        "equal or larger than your settings for max.message.bytes, both at a broker and topic level.")
  }

  /**
   * Truncate the log for each partition's epoch based on leader's returned epoch and offset.
   * The logic for finding the truncation offset is implemented in AbstractFetcherThread.getOffsetTruncationState
   */
  override def truncate(tp: TopicPartition, offsetTruncationState: OffsetTruncationState): Unit = {
    val partition = replicaMgr.getPartitionOrException(tp)
    val log = partition.localLogOrException

    partition.truncateTo(offsetTruncationState.offset, isFuture = false)

    if (offsetTruncationState.offset < log.highWatermark)
      warn(s"Truncating $tp to offset ${offsetTruncationState.offset} below high watermark " +
        s"${log.highWatermark}")

    // mark the future replica for truncation only when we do last truncation
    if (offsetTruncationState.truncationCompleted)
      replicaMgr.replicaAlterLogDirsManager.markPartitionsForTruncation(brokerConfig.brokerId, tp,
        offsetTruncationState.offset)
  }

  override protected def truncateFullyAndStartAt(topicPartition: TopicPartition, offset: Long): Unit = {
    val partition = replicaMgr.getPartitionOrException(topicPartition)
    partition.truncateFullyAndStartAt(offset, isFuture = false)
  }

  override protected def buildRemoteLogAuxState(partition: TopicPartition,
                                                currentLeaderEpoch: Int,
                                                leaderLocalLogStartOffset: Long,
                                                leaderLogStartOffset: Long): Unit = {
    replicaMgr.localLog(partition).foreach(log =>
      if (log.remoteLogEnabled()) {
        replicaMgr.remoteLogManager.foreach(rlm => {
          var rlsMetadata: Optional[RemoteLogSegmentMetadata] = Optional.empty()
          val epoch = log.leaderEpochCache.flatMap(cache => cache.epochForOffset(leaderLocalLogStartOffset))
          if (epoch.isDefined) {
            rlsMetadata = rlm.fetchRemoteLogSegmentMetadata(partition, epoch.get, leaderLocalLogStartOffset)
          } else {
            // If epoch is not available, then it might be possible that this broker might lost its entire local storage.
            // We may also have to build the leader epoch cache. To find out the remote log segment metadata for the
            // leaderLocalLogStartOffset-1, start from the current leader epoch and subtract one to the epoch till
            // finding the metdata.
            var previousLeaderEpoch = currentLeaderEpoch
            while (!rlsMetadata.isPresent && previousLeaderEpoch >= 0) {
              rlsMetadata = rlm.fetchRemoteLogSegmentMetadata(partition, previousLeaderEpoch, leaderLocalLogStartOffset-1)
              previousLeaderEpoch -= 1
            }
          }
          if (rlsMetadata.isPresent) {
            val epochStream = rlm.storageManager().fetchIndex(rlsMetadata.get(), IndexType.LEADER_EPOCH)
            val epochs = readLeaderEpochCheckpoint(epochStream, log.dir)

            // Truncate the existing local log before restoring the leader epoch cache and producer snapshots.
            truncateFullyAndStartAt(partition, leaderLocalLogStartOffset)

            log.maybeIncrementLogStartOffset(leaderLogStartOffset, LeaderOffsetIncremented)
            epochs.foreach(epochEntry => {
              log.leaderEpochCache.map(cache => cache.assign(epochEntry.epoch, epochEntry.startOffset))
            })
            info(s"Updated the epoch cache from remote tier till offset: $leaderLocalLogStartOffset " +
              s"with size: ${epochs.size} for $partition")

            // restore producer snapshot
            val snapshotFile = UnifiedLog.producerSnapshotFile(log.dir, leaderLocalLogStartOffset)
            Files.copy(rlm.storageManager().fetchIndex(rlsMetadata.get(), IndexType.PRODUCER_SNAPSHOT),
              snapshotFile.toPath, StandardCopyOption.REPLACE_EXISTING)
            log.producerStateManager.reloadSegments()
            log.loadProducerState(leaderLocalLogStartOffset)
            info(s"Built the leader epoch cache and producer snapshots from remote tier for $partition. " +
              s"Active producers: ${log.producerStateManager.activeProducers.size}, LeaderLogStartOffset: $leaderLogStartOffset")
          } else {
            throw new RemoteStorageException(s"Couldn't build the state from remote store for partition: $partition, " +
              s"currentLeaderEpoch: $currentLeaderEpoch, leaderLocalLogStartOffset: $leaderLocalLogStartOffset, " +
              s"leaderLogStartOffset: $leaderLogStartOffset, epoch: $epoch as the previous remote log segment " +
              s"metadata was not found")
          }
        })
      }
    )
  }

  private def readLeaderEpochCheckpoint(stream: InputStream,
                                        dir: File): List[EpochEntry] = {
    val tmpFile = new File(dir, "leader-epoch-checkpoint" + TmpFileSuffix)
    Files.copy(stream, tmpFile.toPath)
    val epochEntries = new LeaderEpochCheckpointFile(tmpFile).checkpoint.read().toList
    if (!tmpFile.delete()) {
      logger.warn("Unable to delete the temporary leader epoch checkpoint file: {}", tmpFile.getAbsolutePath)
    }
    epochEntries
  }
}
