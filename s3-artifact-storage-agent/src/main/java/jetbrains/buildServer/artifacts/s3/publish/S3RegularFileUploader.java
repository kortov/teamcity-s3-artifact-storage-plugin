/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.artifacts.s3.publish;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;
import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.ArtifactPublishingFailedException;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.ssl.TrustedCertificatesDirectory;
import jetbrains.buildServer.artifacts.ArtifactDataInstance;
import jetbrains.buildServer.artifacts.s3.S3Util;
import jetbrains.buildServer.artifacts.s3.SSLParamUtil;
import jetbrains.buildServer.artifacts.s3.retry.LoggingRetrier;
import jetbrains.buildServer.artifacts.s3.retry.Retrier;
import jetbrains.buildServer.artifacts.s3.retry.RetrierExponentialDelay;
import jetbrains.buildServer.artifacts.s3.retry.RetrierImpl;
import jetbrains.buildServer.util.CollectionsUtil;
import jetbrains.buildServer.util.Converter;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.amazon.AWSException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.artifacts.s3.S3Util.getBucketName;

public class S3RegularFileUploader implements S3FileUploader {

  private static final Logger LOG = Logger.getInstance(S3RegularFileUploader.class.getName());

  private boolean isDestinationPrepared = false;
  private BuildAgentConfiguration myBuildAgentConfiguration;

  public S3RegularFileUploader(@NotNull final BuildAgentConfiguration buildAgentConfiguration) {
    myBuildAgentConfiguration = buildAgentConfiguration;
  }

  @NotNull
  @Override
  public Collection<ArtifactDataInstance> publishFiles(@NotNull final AgentRunningBuild build,
                                                       @NotNull final String pathPrefix,
                                                       @NotNull final Map<File, String> filesToPublish) {
    final String homeDir = myBuildAgentConfiguration.getAgentHomeDirectory().getPath();
    final String certDirectory = TrustedCertificatesDirectory.getAllCertificatesDirectoryFromHome(homeDir);
    final int numberOfRetries = S3Util.getNumberOfRetries(build.getSharedConfigParameters());
    final int retryDelay = S3Util.getRetryDelayInMs(build.getSharedConfigParameters());

    final Map<String, String> params = S3Util.validateParameters(SSLParamUtil.putSslDirectory(build.getArtifactStorageSettings(), certDirectory));
    final String bucketName = getBucketName(params);

    try {
      prepareDestination(bucketName, params);
      final List<ArtifactDataInstance> artifacts = new ArrayList<ArtifactDataInstance>();
      final Retrier retrier = new RetrierImpl(numberOfRetries)
        .registerListener(new LoggingRetrier(LOG))
        .registerListener(new RetrierExponentialDelay(retryDelay));
      S3Util.withTransferManager(params, new jetbrains.buildServer.util.amazon.S3Util.WithTransferManager<Upload>() {
        @NotNull
        @Override
        public Collection<Upload> run(@NotNull final TransferManager transferManager) {
          return CollectionsUtil.convertAndFilterNulls(filesToPublish.entrySet(), new Converter<Upload, Map.Entry<File, String>>() {
            @Override
            public Upload createFrom(@NotNull final Map.Entry<File, String> entry) {
              return retrier.execute(new Callable<Upload>() {
                @Override
                public String toString() {
                  final String filename = entry.getKey() != null ? entry.getKey().getName() : "null";
                  return "publishing file '" + filename + "'";
                }

                @Override
                public Upload call() throws AmazonClientException {
                  final File file = entry.getKey();
                  final String path = entry.getValue();
                  final String artifactPath = S3Util.normalizeArtifactPath(path, file);
                  final String objectKey = pathPrefix + artifactPath;

                  artifacts.add(ArtifactDataInstance.create(artifactPath, file.length()));

                  final ObjectMetadata metadata = new ObjectMetadata();
                  metadata.setContentType(S3Util.getContentType(file));
                  final PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, objectKey, file)
                    .withCannedAcl(CannedAccessControlList.Private)
                    .withMetadata(metadata);
                  final Upload upload = transferManager.upload(putObjectRequest);
                  try {
                    upload.waitForUploadResult();
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                  return upload;
                }
              });
            }
          });
        }
      });
      return artifacts;
    } catch (ArtifactPublishingFailedException t) {
      throw t;
    } catch (Throwable t) {
      final AWSException awsException = new AWSException(t);
      final String details = awsException.getDetails();

      if (StringUtil.isNotEmpty(details)) {
        final String message = awsException.getMessage() + details;
        LOG.warn(message);
        build.getBuildLogger().error(message);
      }

      throw new ArtifactPublishingFailedException(awsException.getMessage(), false, awsException);
    }
  }

  private void prepareDestination(final String bucketName,
                                  final Map<String, String> params) throws Throwable {
    if (isDestinationPrepared) return;

    S3Util.withS3Client(params, new S3Util.WithS3<Void, Throwable>() {
      @Nullable
      @Override
      public Void run(@NotNull AmazonS3 s3Client) {
        // Minio does not support #doesBucketExistsV2
        // noinspection deprecation
        if (s3Client.doesBucketExist(bucketName)) {
          isDestinationPrepared = true;
          return null;
        }
        throw new ArtifactPublishingFailedException("Target S3 artifact bucket " + bucketName + " doesn't exist", false, null);
      }
    });
  }
}
