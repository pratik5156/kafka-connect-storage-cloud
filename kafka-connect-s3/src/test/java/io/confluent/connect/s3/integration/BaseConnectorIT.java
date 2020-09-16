/*
 * Copyright [2020 - 2020] Confluent Inc.
 */

package io.confluent.connect.s3.integration;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import io.confluent.common.utils.IntegrationTest;
import org.apache.kafka.connect.runtime.AbstractStatus;
import org.apache.kafka.connect.runtime.rest.entities.ConnectorStateInfo;
import org.apache.kafka.connect.util.clusters.EmbeddedConnectCluster;
import org.apache.kafka.test.TestUtils;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Category(IntegrationTest.class)
public abstract class BaseConnectorIT {

  private static final Logger log = LoggerFactory.getLogger(BaseConnectorIT.class);

  protected static final long CONSUME_MAX_DURATION_MS = TimeUnit.SECONDS.toMillis(60);
  protected static final long CONNECTOR_STARTUP_DURATION_MS = TimeUnit.SECONDS.toMillis(6000);
  protected static final String JSON_FORMAT_CLASS = "io.confluent.connect.s3.format.json.JsonFormat";
  protected static final String S3_BUCKET = "sink-test-bucket";

  protected static AmazonS3 s3RootClient;

  protected static PumbaPauseContainer pumbaPauseContainer;

  protected EmbeddedConnectCluster connect;

  protected void startConnect() throws IOException {
    //Map<String, String> props = new HashMap<>();
    //props.put("consumer.max.poll.records","1");
    connect = new EmbeddedConnectCluster.Builder()
        .name("my-connect-cluster")
        //.workerProps(props)
        .build();
    connect.start();
  }

  protected void stopConnect() {
    connect.stop();
  }

  /**
   * Wait up to {@link #CONNECTOR_STARTUP_DURATION_MS maximum time limit} for the connector with the given
   * name to start the specified number of tasks.
   *
   * @param name the name of the connector
   * @param numTasks the minimum number of tasks that are expected
   * @return the time this method discovered the connector has started, in milliseconds past epoch
   * @throws InterruptedException if this was interrupted
   */
  protected long waitForConnectorToStart(String name, int numTasks) throws InterruptedException {
    TestUtils.waitForCondition(
        () -> assertConnectorAndTasksRunning(name, numTasks).orElse(false),
        CONNECTOR_STARTUP_DURATION_MS,
        "Connector tasks did not start in time."
    );
    return System.currentTimeMillis();
  }

  /**
   * Confirm that a connector with an exact number of tasks is running.
   *
   * @param connectorName the connector
   * @param numTasks the minimum number of tasks
   * @return true if the connector and tasks are in RUNNING state; false otherwise
   */
  protected Optional<Boolean> assertConnectorAndTasksRunning(String connectorName, int numTasks) {
    try {
      ConnectorStateInfo info = connect.connectorStatus(connectorName);
      boolean result = info != null
                       && info.tasks().size() >= numTasks
                       && info.connector().state().equals(AbstractStatus.State.RUNNING.toString())
                       && info.tasks().stream().allMatch(s -> s.state().equals(AbstractStatus.State.RUNNING.toString()));
      return Optional.of(result);
    } catch (Exception e) {
      log.error("Could not check connector state info.", e);
      return Optional.empty();
    }
  }

  protected void waitForConnectorToCompleteSendingRecords(long noOfRecordsProduced, int flushSize, String bucketname) throws InterruptedException {
    TestUtils.waitForCondition(
      () -> assertSuccess(noOfRecordsProduced, flushSize, bucketname).orElse(false),
      CONNECTOR_STARTUP_DURATION_MS,
      "Connector could not send all records in time."
    );
  }

  protected Optional<Boolean> assertSuccess(long noOfRecordsProduced, int flushSize, String bucketname) {
    try {
    int noOfObjectsInS3 = getNoOfObjectsInS3(bucketname);
    boolean result = noOfObjectsInS3 == Math.ceil(noOfRecordsProduced/flushSize);
    return Optional.of(result);
  } catch (Exception e) {
    log.error("Could not check S3 state", e);
    return Optional.empty();
  }
  }

  protected int getNoOfObjectsInS3(String bucketName) {
    ListObjectsV2Request req = new ListObjectsV2Request().withBucketName(bucketName).withMaxKeys(100);
    ListObjectsV2Result result;
    List<S3Object> records = new ArrayList<>();

    do {
      result = s3RootClient.listObjectsV2(req);

      for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {
        if(objectSummary.getSize() > 0) {
          records.add(s3RootClient.getObject(bucketName, objectSummary.getKey()));
        }
      }
      // If there are more than maxKeys keys in the bucket, get a continuation token
      // and list the next objects.
      String token = result.getNextContinuationToken();
      req.setContinuationToken(token);
    } while (result.isTruncated());
    return records.size();
  }

  protected int waitForFetchingStorageObjectsInS3(String bucketName) throws InterruptedException {
    return waitForFetchingStorageObjectsInS3(bucketName, CONSUME_MAX_DURATION_MS);
  }

  protected int waitForFetchingStorageObjectsInS3(String bucketName, long maxWaitMs) throws InterruptedException {
    long startTime = System.currentTimeMillis();
    while(System.currentTimeMillis() - startTime < maxWaitMs) {
      Thread.sleep(Math.min(maxWaitMs, 100L));
    }
    return getNoOfObjectsInS3(bucketName);
  }

  protected void startPumbaPauseContainer() {
    pumbaPauseContainer = new PumbaPauseContainer();
    pumbaPauseContainer.start();
  }

}
