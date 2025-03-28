/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.s3;

import static org.apache.logging.log4j.util.Strings.isNotBlank;

import alex.mojaki.s3upload.MultiPartOutputStream;
import alex.mojaki.s3upload.StreamTransferManager;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest.KeyVersion;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import io.airbyte.commons.string.Strings;
import io.airbyte.integrations.destination.NamingConventionTransformer;
import io.airbyte.integrations.destination.record_buffer.SerializableBuffer;
import io.airbyte.integrations.destination.s3.util.StreamTransferManagerHelper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.io.FilenameUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class S3StorageOperations implements BlobStorageOperations {

  private static final Logger LOGGER = LoggerFactory.getLogger(S3StorageOperations.class);

  private static final int DEFAULT_UPLOAD_THREADS = 10; // The S3 cli uses 10 threads by default.
  private static final int DEFAULT_QUEUE_CAPACITY = DEFAULT_UPLOAD_THREADS;
  private static final int DEFAULT_PART_SIZE = 10;
  private static final int UPLOAD_RETRY_LIMIT = 3;

  private static final String FORMAT_VARIABLE_NAMESPACE = "${NAMESPACE}";
  private static final String FORMAT_VARIABLE_STREAM_NAME = "${STREAM_NAME}";
  private static final String FORMAT_VARIABLE_YEAR = "${YEAR}";
  private static final String FORMAT_VARIABLE_MONTH = "${MONTH}";
  private static final String FORMAT_VARIABLE_DAY = "${DAY}";
  private static final String FORMAT_VARIABLE_HOUR = "${HOUR}";
  private static final String FORMAT_VARIABLE_MINUTE = "${MINUTE}";
  private static final String FORMAT_VARIABLE_SECOND = "${SECOND}";
  private static final String FORMAT_VARIABLE_MILLISECOND = "${MILLISECOND}";
  private static final String FORMAT_VARIABLE_EPOCH = "${EPOCH}";
  private static final String FORMAT_VARIABLE_UUID = "${UUID}";
  private static final String GZ_FILE_EXTENSION = "gz";

  private final NamingConventionTransformer nameTransformer;
  protected final S3DestinationConfig s3Config;
  protected AmazonS3 s3Client;

  public S3StorageOperations(final NamingConventionTransformer nameTransformer, final AmazonS3 s3Client, final S3DestinationConfig s3Config) {
    this.nameTransformer = nameTransformer;
    this.s3Client = s3Client;
    this.s3Config = s3Config;
  }

  @Override
  public String getBucketObjectPath(final String namespace, final String streamName, final DateTime writeDatetime, final String customPathFormat) {
    final String namespaceStr = nameTransformer.getNamespace(isNotBlank(namespace) ? namespace : "");
    final String streamNameStr = nameTransformer.getIdentifier(streamName);
    return nameTransformer.applyDefaultCase(
        customPathFormat
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_NAMESPACE), namespaceStr)
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_STREAM_NAME), streamNameStr)
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_YEAR), String.format("%s", writeDatetime.year().get()))
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_MONTH), String.format("%02d", writeDatetime.monthOfYear().get()))
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_DAY), String.format("%02d", writeDatetime.dayOfMonth().get()))
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_HOUR), String.format("%02d", writeDatetime.hourOfDay().get()))
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_MINUTE), String.format("%02d", writeDatetime.minuteOfHour().get()))
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_SECOND), String.format("%02d", writeDatetime.secondOfMinute().get()))
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_MILLISECOND), String.format("%04d", writeDatetime.millisOfSecond().get()))
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_EPOCH), String.format("%d", writeDatetime.getMillis()))
            .replaceAll(Pattern.quote(FORMAT_VARIABLE_UUID), String.format("%s", UUID.randomUUID()))
            .replaceAll("/+", "/"));
  }

  @Override
  public void createBucketObjectIfNotExists(final String objectPath) {
    final String bucket = s3Config.getBucketName();
    if (!s3Client.doesBucketExistV2(bucket)) {
      LOGGER.info("Bucket {} does not exist; creating...", bucket);
      s3Client.createBucket(bucket);
      LOGGER.info("Bucket {} has been created.", bucket);
    }
    if (!s3Client.doesObjectExist(bucket, objectPath)) {
      LOGGER.info("Storage Object {}/{} does not exist in bucket; creating...", bucket, objectPath);
      s3Client.putObject(bucket, objectPath.endsWith("/") ? objectPath : objectPath + "/", "");
      LOGGER.info("Storage Object {}/{} has been created in bucket.", bucket, objectPath);
    }
  }

  @Override
  public String uploadRecordsToBucket(final SerializableBuffer recordsData,
                                      final String namespace,
                                      final String streamName,
                                      final String objectPath) {
    final List<Exception> exceptionsThrown = new ArrayList<>();
    while (exceptionsThrown.size() < UPLOAD_RETRY_LIMIT) {
      if (exceptionsThrown.size() > 0) {
        LOGGER.info("Retrying to upload records into storage {} ({}/{}})", objectPath, exceptionsThrown.size(), UPLOAD_RETRY_LIMIT);
        // Force a reconnection before retrying in case error was due to network issues...
        s3Client = s3Config.resetS3Client();
      }

      try {
        return loadDataIntoBucket(objectPath, recordsData);
      } catch (final Exception e) {
        LOGGER.error("Failed to upload records into storage {}", objectPath, e);
        exceptionsThrown.add(e);
      }
    }
    throw new RuntimeException(String.format("Exceptions thrown while uploading records into storage: %s", Strings.join(exceptionsThrown, "\n")));
  }

  /**
   * Upload the file from {@code recordsData} to S3 and simplify the filename as <partId>.<extension>.
   *
   * @return the uploaded filename, which is different from the serialized buffer filename
   */
  private String loadDataIntoBucket(final String objectPath, final SerializableBuffer recordsData) throws IOException {
    final long partSize = s3Config.getFormatConfig() != null ? s3Config.getFormatConfig().getPartSize() : DEFAULT_PART_SIZE;
    final String bucket = s3Config.getBucketName();
    final String fullObjectKey = objectPath + getPartId(objectPath) + getExtension(recordsData.getFilename());
    final StreamTransferManager uploadManager = StreamTransferManagerHelper
        .getDefault(bucket, fullObjectKey, s3Client, partSize)
        .checkIntegrity(true)
        .numUploadThreads(DEFAULT_UPLOAD_THREADS)
        .queueCapacity(DEFAULT_QUEUE_CAPACITY);
    boolean succeeded = false;
    try (final MultiPartOutputStream outputStream = uploadManager.getMultiPartOutputStreams().get(0);
        final InputStream dataStream = recordsData.getInputStream()) {
      dataStream.transferTo(outputStream);
      succeeded = true;
    } catch (final Exception e) {
      LOGGER.error("Failed to load data into storage {}", objectPath, e);
      throw new RuntimeException(e);
    } finally {
      if (!succeeded) {
        uploadManager.abort();
      } else {
        uploadManager.complete();
      }
    }
    if (!s3Client.doesObjectExist(bucket, fullObjectKey)) {
      LOGGER.error("Failed to upload data into storage, object {} not found", fullObjectKey);
      throw new RuntimeException("Upload failed");
    }
    final String newFilename = getFilename(fullObjectKey);
    LOGGER.info("Uploaded buffer file to storage: {} -> {} (filename: {})", recordsData.getFilename(), fullObjectKey, newFilename);
    return newFilename;
  }

  @VisibleForTesting
  static String getFilename(final String fullPath) {
    return fullPath.substring(fullPath.lastIndexOf("/") + 1);
  }

  protected static String getExtension(final String filename) {
    final String result = FilenameUtils.getExtension(filename);
    if (result.isBlank()) {
      return result;
    } else if (GZ_FILE_EXTENSION.equals(result)) {
      return getExtension(filename.substring(0, filename.length() - 3)) + "." + GZ_FILE_EXTENSION;
    }
    return "." + result;
  }

  private String getPartId(final String objectPath) {
    final String bucket = s3Config.getBucketName();
    final ObjectListing objects = s3Client.listObjects(bucket, objectPath);
    if (objects.isTruncated()) {
      // bucket contains too many objects, use an uuid instead
      return UUID.randomUUID().toString();
    } else {
      return Integer.toString(objects.getObjectSummaries().size());
    }
  }

  @Override
  public void dropBucketObject(final String objectPath) {
    cleanUpBucketObject(objectPath, List.of());
  }

  @Override
  public void cleanUpBucketObject(final String namespace, final String streamName, final String objectPath, final String pathFormat) {
    final String bucket = s3Config.getBucketName();
    ObjectListing objects = s3Client.listObjects(new ListObjectsRequest()
        .withBucketName(bucket)
        .withPrefix(objectPath)
        // pathFormat may use subdirectories under the objectPath to organize files
        // so we need to recursively list them and filter files matching the pathFormat
        .withDelimiter(""));
    final Pattern regexFormat = Pattern.compile(getRegexFormat(namespace, streamName, pathFormat));
    while (objects.getObjectSummaries().size() > 0) {
      final List<KeyVersion> keysToDelete = objects.getObjectSummaries()
          .stream()
          .filter(obj -> regexFormat.matcher(obj.getKey()).matches())
          .map(obj -> new KeyVersion(obj.getKey()))
          .toList();
      cleanUpObjects(bucket, keysToDelete);
      LOGGER.info("Storage bucket {} has been cleaned-up ({} objects matching {} were deleted)...", objectPath, keysToDelete.size(), regexFormat);
      if (objects.isTruncated()) {
        objects = s3Client.listNextBatchOfObjects(objects);
      } else {
        break;
      }
    }
  }

  protected String getRegexFormat(final String namespace, final String streamName, final String pathFormat) {
    final String namespaceStr = nameTransformer.getNamespace(isNotBlank(namespace) ? namespace : "");
    final String streamNameStr = nameTransformer.getIdentifier(streamName);
    return nameTransformer.applyDefaultCase(pathFormat
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_NAMESPACE), namespaceStr)
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_STREAM_NAME), streamNameStr)
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_YEAR), "[0-9]{4}")
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_MONTH), "[0-9]{2}")
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_DAY), "[0-9]{2}")
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_HOUR), "[0-9]{2}")
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_MINUTE), "[0-9]{2}")
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_SECOND), "[0-9]{2}")
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_MILLISECOND), "[0-9]{4}")
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_EPOCH), "[0-9]+")
        .replaceAll(Pattern.quote(FORMAT_VARIABLE_UUID), ".*")
        .replaceAll("/+", "/")
        // match part_id and extension at the end
        + ".*");
  }

  @Override
  public void cleanUpBucketObject(final String objectPath, final List<String> stagedFiles) {
    final String bucket = s3Config.getBucketName();
    ObjectListing objects = s3Client.listObjects(bucket, objectPath);
    while (objects.getObjectSummaries().size() > 0) {
      final List<KeyVersion> keysToDelete = objects.getObjectSummaries()
          .stream()
          .filter(obj -> stagedFiles.isEmpty() || stagedFiles.contains(obj.getKey()))
          .map(obj -> new KeyVersion(obj.getKey()))
          .toList();
      cleanUpObjects(bucket, keysToDelete);
      LOGGER.info("Storage bucket {} has been cleaned-up ({} objects were deleted)...", objectPath, keysToDelete.size());
      if (objects.isTruncated()) {
        objects = s3Client.listNextBatchOfObjects(objects);
      } else {
        break;
      }
    }
  }

  protected void cleanUpObjects(final String bucket, final List<KeyVersion> keysToDelete) {
    if (!keysToDelete.isEmpty()) {
      LOGGER.info("Deleting objects {}", String.join(", ", keysToDelete.stream().map(KeyVersion::getKey).toList()));
      s3Client.deleteObjects(new DeleteObjectsRequest(bucket).withKeys(keysToDelete));
    }
  }

  @Override
  public boolean isValidData(final JsonNode jsonNode) {
    return true;
  }

}
