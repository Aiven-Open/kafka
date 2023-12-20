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

import com.yammer.metrics.core.Meter
import kafka.network.RequestChannel
import org.apache.kafka.common.memory.MemoryPool
import org.apache.kafka.common.network.{ClientInformation, ListenerName}
import org.apache.kafka.common.protocol.ApiKeys
import org.apache.kafka.common.requests.{RequestContext, RequestHeader}
import org.apache.kafka.common.security.auth.{KafkaPrincipal, SecurityProtocol}
import org.apache.kafka.common.utils.{MockTime, Time}
import org.apache.kafka.server.log.remote.storage.{RemoteLogManagerConfig, RemoteStorageMetrics}
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, times, verify, when}

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class KafkaRequestHandlerTest {

  @Test
  def testCallbackTiming(): Unit = {
    val time = new MockTime()
    val startTime = time.nanoseconds()
    val metrics = new RequestChannel.Metrics(None)
    val requestChannel = new RequestChannel(10, "", time, metrics)
    val apiHandler = mock(classOf[ApiRequestHandler])
    try {
      val handler = new KafkaRequestHandler(0, 0, mock(classOf[Meter]), new AtomicInteger(1), requestChannel, apiHandler, time)

      val request = makeRequest(time, metrics)
      requestChannel.sendRequest(request)

      when(apiHandler.handle(ArgumentMatchers.eq(request), any())).thenAnswer { _ =>
        time.sleep(2)
        // Prepare the callback.
        val callback = KafkaRequestHandler.wrapAsyncCallback(
          (reqLocal: RequestLocal, ms: Int) => {
            time.sleep(ms)
            handler.stop()
          },
          RequestLocal.NoCaching)
        // Execute the callback asynchronously.
        CompletableFuture.runAsync(() => callback(1))
        request.apiLocalCompleteTimeNanos = time.nanoseconds
      }

      handler.run()

      assertEquals(startTime, request.requestDequeueTimeNanos)
      assertEquals(startTime + 2000000, request.apiLocalCompleteTimeNanos)
      assertEquals(Some(startTime + 2000000), request.callbackRequestDequeueTimeNanos)
      assertEquals(Some(startTime + 3000000), request.callbackRequestCompleteTimeNanos)
    } finally {
      metrics.close()
    }
  }

  @Test
  def testCallbackTryCompleteActions(): Unit = {
    val time = new MockTime()
    val metrics = mock(classOf[RequestChannel.Metrics])
    val apiHandler = mock(classOf[ApiRequestHandler])
    val requestChannel = new RequestChannel(10, "", time, metrics)
    val handler = new KafkaRequestHandler(0, 0, mock(classOf[Meter]), new AtomicInteger(1), requestChannel, apiHandler, time)

    var handledCount = 0
    var tryCompleteActionCount = 0

    val request = makeRequest(time, metrics)
    requestChannel.sendRequest(request)

    when(apiHandler.handle(ArgumentMatchers.eq(request), any())).thenAnswer { _ =>
      handledCount = handledCount + 1
      // Prepare the callback.
      val callback = KafkaRequestHandler.wrapAsyncCallback(
        (reqLocal: RequestLocal, ms: Int) => {
          handler.stop()
        },
        RequestLocal.NoCaching)
      // Execute the callback asynchronously.
      CompletableFuture.runAsync(() => callback(1))
    }

    when(apiHandler.tryCompleteActions()).thenAnswer { _ =>
      tryCompleteActionCount = tryCompleteActionCount + 1
    }

    handler.run()

    assertEquals(1, handledCount)
    assertEquals(1, tryCompleteActionCount)
  }

  @Test
  def testHandlingCallbackOnNewThread(): Unit = {
    val time = new MockTime()
    val metrics = mock(classOf[RequestChannel.Metrics])
    val apiHandler = mock(classOf[ApiRequestHandler])
    val requestChannel = new RequestChannel(10, "", time, metrics)
    val handler = new KafkaRequestHandler(0, 0, mock(classOf[Meter]), new AtomicInteger(1), requestChannel, apiHandler, time)

    val originalRequestLocal = mock(classOf[RequestLocal])

    var handledCount = 0

    val request = makeRequest(time, metrics)
    requestChannel.sendRequest(request)

    when(apiHandler.handle(ArgumentMatchers.eq(request), any())).thenAnswer { _ =>
      // Prepare the callback.
      val callback = KafkaRequestHandler.wrapAsyncCallback(
        (reqLocal: RequestLocal, ms: Int) => {
          reqLocal.bufferSupplier.close()
          handledCount = handledCount + 1
          handler.stop()
        },
        originalRequestLocal)
      // Execute the callback asynchronously.
      CompletableFuture.runAsync(() => callback(1))
    }

    handler.run()
    // Verify that we don't use the request local that we passed in.
    verify(originalRequestLocal, times(0)).bufferSupplier
    assertEquals(1, handledCount)
  }

  @ParameterizedTest
  @ValueSource(booleans = Array(true, false))
  def testTopicStats(systemRemoteStorageEnabled: Boolean): Unit = {
    val topic = "topic"
    val props = kafka.utils.TestUtils.createDummyBrokerConfig()
    props.setProperty(RemoteLogManagerConfig.REMOTE_LOG_STORAGE_SYSTEM_ENABLE_PROP, systemRemoteStorageEnabled.toString)
    val brokerTopicStats = new BrokerTopicStats(java.util.Optional.of(KafkaConfig.fromProps(props)))
    brokerTopicStats.topicStats(topic)
    val gaugeMetrics = Set(
      RemoteStorageMetrics.REMOTE_COPY_LOG_BYTES_METRIC.getName,
      RemoteStorageMetrics.REMOTE_LOG_SIZE_COMPUTATION_TIME_METRIC.getName,
      RemoteStorageMetrics.REMOTE_LOG_METADATA_COUNT_METRIC.getName)

    RemoteStorageMetrics.brokerTopicStatsMetrics.forEach(metric => {
      if (systemRemoteStorageEnabled) {
        if (!gaugeMetrics.contains(metric.getName)) {
          assertTrue(brokerTopicStats.topicStats(topic).metricMap.contains(metric.getName), "the metric is missing: " + metric.getName)
        } else {
          assertFalse(brokerTopicStats.topicStats(topic).metricMap.contains(metric.getName), "the metric should not appear: " + metric.getName)
        }
      } else {
        assertFalse(brokerTopicStats.topicStats(topic).metricMap.contains(metric.getName))
      }
    })
    gaugeMetrics.foreach(metricName => {
      if (systemRemoteStorageEnabled) {
        assertTrue(brokerTopicStats.topicStats(topic).metricGaugeMap.contains(metricName), "The metric is missing:" + metricName)
      } else {
        assertFalse(brokerTopicStats.topicStats(topic).metricGaugeMap.contains(metricName), "The metric should appear:" + metricName)
      }
    })
  }

  def makeRequest(time: Time, metrics: RequestChannel.Metrics): RequestChannel.Request = {
    // Make unsupported API versions request to avoid having to parse a real request
    val requestHeader = mock(classOf[RequestHeader])
    when(requestHeader.apiKey()).thenReturn(ApiKeys.API_VERSIONS)
    when(requestHeader.apiVersion()).thenReturn(0.toShort)

    val context = new RequestContext(requestHeader, "0", mock(classOf[InetAddress]), new KafkaPrincipal("", ""),
      new ListenerName(""), SecurityProtocol.PLAINTEXT, mock(classOf[ClientInformation]), false)
    new RequestChannel.Request(0, context, time.nanoseconds(),
      mock(classOf[MemoryPool]), ByteBuffer.allocate(0), metrics)
  }

  def setupBrokerTopicMetrics(systemRemoteStorageEnabled: Boolean = true): BrokerTopicMetrics = {
    val topic = "topic"
    val props = kafka.utils.TestUtils.createDummyBrokerConfig()
    props.setProperty(RemoteLogManagerConfig.REMOTE_LOG_STORAGE_SYSTEM_ENABLE_PROP, systemRemoteStorageEnabled.toString)
    new BrokerTopicMetrics(Option.apply(topic), java.util.Optional.of(KafkaConfig.fromProps(props)))
  }

  @ParameterizedTest
  @ValueSource(booleans = Array(true, false))
  def testSingularCopyLagBytesMetric(systemRemoteStorageEnabled: Boolean): Unit = {
    val brokerTopicMetrics = setupBrokerTopicMetrics(systemRemoteStorageEnabled)

    if (systemRemoteStorageEnabled) {
      brokerTopicMetrics.recordRemoteCopyBytesLag(0, 100);
      brokerTopicMetrics.recordRemoteCopyBytesLag(1, 150);
      brokerTopicMetrics.recordRemoteCopyBytesLag(2, 250);
      assertEquals(500, brokerTopicMetrics.remoteCopyBytesLag)
    } else {
      assertEquals(None, brokerTopicMetrics.metricGaugeMap.get(RemoteStorageMetrics.REMOTE_COPY_LOG_BYTES_METRIC.getName))
    }
  }

  @Test
  def testMultipleCopyLagBytesMetrics(): Unit = {
    val brokerTopicMetrics = setupBrokerTopicMetrics()

    brokerTopicMetrics.recordRemoteCopyBytesLag(0, 1);
    brokerTopicMetrics.recordRemoteCopyBytesLag(1, 2);
    brokerTopicMetrics.recordRemoteCopyBytesLag(2, 3);

    brokerTopicMetrics.recordRemoteCopyBytesLag(0, 4);
    brokerTopicMetrics.recordRemoteCopyBytesLag(1, 5);
    brokerTopicMetrics.recordRemoteCopyBytesLag(2, 6);

    assertEquals(15, brokerTopicMetrics.remoteCopyBytesLag)
  }

  @Test
  def testCopyLagBytesMetricWithPartitionExpansion(): Unit = {
    val brokerTopicMetrics = setupBrokerTopicMetrics()

    brokerTopicMetrics.recordRemoteCopyBytesLag(0, 1);
    brokerTopicMetrics.recordRemoteCopyBytesLag(1, 2);

    assertEquals(3, brokerTopicMetrics.remoteCopyBytesLag)

    brokerTopicMetrics.recordRemoteCopyBytesLag(2, 3);

    assertEquals(6, brokerTopicMetrics.remoteCopyBytesLag)
  }

  @Test
  def testCopyLagBytesMetricWithPartitionShrinking(): Unit = {
    val brokerTopicMetrics = setupBrokerTopicMetrics()

    brokerTopicMetrics.recordRemoteCopyBytesLag(0, 1);
    brokerTopicMetrics.recordRemoteCopyBytesLag(1, 2);

    assertEquals(3, brokerTopicMetrics.remoteCopyBytesLag)

    brokerTopicMetrics.removeRemoteCopyBytesLag(1);

    assertEquals(1, brokerTopicMetrics.remoteCopyBytesLag)
  }

  @Test
  def testCopyLagBytesMetricWithRemovingNonexistentPartitions(): Unit = {
    val brokerTopicMetrics = setupBrokerTopicMetrics()

    brokerTopicMetrics.recordRemoteCopyBytesLag(0, 1);
    brokerTopicMetrics.recordRemoteCopyBytesLag(1, 2);

    assertEquals(3, brokerTopicMetrics.remoteCopyBytesLag)

    brokerTopicMetrics.removeRemoteCopyBytesLag(3);

    assertEquals(3, brokerTopicMetrics.remoteCopyBytesLag)
  }

  @Test
  def testCopyLagBytesMetricClear(): Unit = {
    val brokerTopicMetrics = setupBrokerTopicMetrics()

    brokerTopicMetrics.recordRemoteCopyBytesLag(0, 1);
    brokerTopicMetrics.recordRemoteCopyBytesLag(1, 2);

    assertEquals(3, brokerTopicMetrics.remoteCopyBytesLag)

    brokerTopicMetrics.close()

    assertEquals(0, brokerTopicMetrics.remoteCopyBytesLag)
  }

  @Test
  def testRemoteLogMetadataCount(): Unit = {
    val brokerTopicMetrics = setupBrokerTopicMetrics()

    assertEquals(0, brokerTopicMetrics.remoteLogMetadataCount)
    brokerTopicMetrics.recordRemoteLogMetadataCount(0, 1)
    assertEquals(1, brokerTopicMetrics.remoteLogMetadataCount)

    brokerTopicMetrics.recordRemoteLogMetadataCount(1, 2)
    brokerTopicMetrics.recordRemoteLogMetadataCount(2, 3)
    assertEquals(6, brokerTopicMetrics.remoteLogMetadataCount)

    brokerTopicMetrics.close()

    assertEquals(0, brokerTopicMetrics.remoteLogMetadataCount)
  }
}
