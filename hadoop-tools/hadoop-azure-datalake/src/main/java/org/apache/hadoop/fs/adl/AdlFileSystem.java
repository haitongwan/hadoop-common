/*
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
 *
 */

package org.apache.hadoop.fs.adl;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.microsoft.azure.datalake.store.ADLStoreClient;
import com.microsoft.azure.datalake.store.ADLStoreOptions;
import com.microsoft.azure.datalake.store.DirectoryEntry;
import com.microsoft.azure.datalake.store.DirectoryEntryType;
import com.microsoft.azure.datalake.store.IfExists;
import com.microsoft.azure.datalake.store.LatencyTracker;
import com.microsoft.azure.datalake.store.oauth2.AccessTokenProvider;
import com.microsoft.azure.datalake.store.oauth2.ClientCredsTokenProvider;
import com.microsoft.azure.datalake.store.oauth2.RefreshTokenBasedTokenProvider;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.InvalidPathException;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Options.Rename;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.adl.oauth2.AzureADTokenProvider;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.VersionInfo;
import static org.apache.hadoop.fs.adl.AdlConfKeys.*;

/**
 * A FileSystem to access Azure Data Lake Store.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class AdlFileSystem extends FileSystem {
  static final String SCHEME = "adl";
  static final int DEFAULT_PORT = 443;
  private URI uri;
  private String userName;
  private boolean overrideOwner;
  private ADLStoreClient adlClient;
  private Path workingDirectory;
  private boolean aclBitStatus;

  // retained for tests
  private AccessTokenProvider tokenProvider;
  private AzureADTokenProvider azureTokenProvider;

  @Override
  public String getScheme() {
    return SCHEME;
  }

  public URI getUri() {
    return uri;
  }

  @Override
  public int getDefaultPort() {
    return DEFAULT_PORT;
  }

  @Override
  public boolean supportsSymlinks() {
    return false;
  }

  /**
   * Called after a new FileSystem instance is constructed.
   *
   * @param storeUri a uri whose authority section names the host, port, etc.
   *                 for this FileSystem
   * @param conf     the configuration
   */
  @Override
  public void initialize(URI storeUri, Configuration conf) throws IOException {
    super.initialize(storeUri, conf);
    this.setConf(conf);
    this.uri = URI
        .create(storeUri.getScheme() + "://" + storeUri.getAuthority());

    try {
      userName = UserGroupInformation.getCurrentUser().getShortUserName();
    } catch (IOException e) {
      userName = "hadoop";
    }

    this.setWorkingDirectory(getHomeDirectory());

    overrideOwner = getConf().getBoolean(ADL_DEBUG_OVERRIDE_LOCAL_USER_AS_OWNER,
        ADL_DEBUG_SET_LOCAL_USER_AS_OWNER_DEFAULT);

    aclBitStatus = conf.getBoolean(ADL_SUPPORT_ACL_BIT_IN_FSPERMISSION,
        ADL_SUPPORT_ACL_BIT_IN_FSPERMISSION_DEFAULT);

    String accountFQDN = null;
    String mountPoint = null;
    String hostname = storeUri.getHost();
    if (!hostname.contains(".") && !hostname.equalsIgnoreCase(
        "localhost")) {  // this is a symbolic name. Resolve it.
      String hostNameProperty = "dfs.adls." + hostname + ".hostname";
      String mountPointProperty = "dfs.adls." + hostname + ".mountpoint";
      accountFQDN = getNonEmptyVal(conf, hostNameProperty);
      mountPoint = getNonEmptyVal(conf, mountPointProperty);
    } else {
      accountFQDN = hostname;
    }

    if (storeUri.getPort() > 0) {
      accountFQDN = accountFQDN + ":" + storeUri.getPort();
    }

    adlClient = ADLStoreClient
        .createClient(accountFQDN, getAccessTokenProvider(conf));

    ADLStoreOptions options = new ADLStoreOptions();
    options.enableThrowingRemoteExceptions();

    if (getTransportScheme().equalsIgnoreCase(INSECURE_TRANSPORT_SCHEME)) {
      options.setInsecureTransport();
    }

    if (mountPoint != null) {
      options.setFilePathPrefix(mountPoint);
    }

    String clusterName = conf.get(ADL_EVENTS_TRACKING_CLUSTERNAME, "UNKNOWN");
    String clusterType = conf.get(ADL_EVENTS_TRACKING_CLUSTERTYPE, "UNKNOWN");

    String clientVersion = ADL_HADOOP_CLIENT_NAME + (StringUtils
        .isEmpty(VersionInfo.getVersion().trim()) ?
        ADL_HADOOP_CLIENT_VERSION.trim() :
        VersionInfo.getVersion().trim());
    options.setUserAgentSuffix(clientVersion + "/" +
        VersionInfo.getVersion().trim() + "/" + clusterName + "/"
        + clusterType);

    adlClient.setOptions(options);

    boolean trackLatency = conf
        .getBoolean(LATENCY_TRACKER_KEY, LATENCY_TRACKER_DEFAULT);
    if (!trackLatency) {
      LatencyTracker.disable();
    }
  }

  /**
   * This method is provided for convenience for derived classes to define
   * custom {@link AzureADTokenProvider} instance.
   *
   * In order to ensure secure hadoop infrastructure and user context for which
   * respective {@link AdlFileSystem} instance is initialized,
   * Loading {@link AzureADTokenProvider} is not sufficient.
   *
   * The order of loading {@link AzureADTokenProvider} is to first invoke
   * {@link #getCustomAccessTokenProvider(Configuration)}, If method return null
   * which means no implementation provided by derived classes, then
   * configuration object is loaded to retrieve token configuration as specified
   * is documentation.
   *
   * Custom token management takes the higher precedence during initialization.
   *
   * @param conf Configuration object
   * @return null if the no custom {@link AzureADTokenProvider} token management
   * is specified.
   * @throws IOException if failed to initialize token provider.
   */
  protected synchronized AzureADTokenProvider getCustomAccessTokenProvider(
      Configuration conf) throws IOException {
    String className = getNonEmptyVal(conf, AZURE_AD_TOKEN_PROVIDER_CLASS_KEY);

    Class<? extends AzureADTokenProvider> azureADTokenProviderClass =
        conf.getClass(AZURE_AD_TOKEN_PROVIDER_CLASS_KEY, null,
            AzureADTokenProvider.class);
    if (azureADTokenProviderClass == null) {
      throw new IllegalArgumentException(
          "Configuration  " + className + " " + "not defined/accessible.");
    }

    azureTokenProvider = ReflectionUtils
        .newInstance(azureADTokenProviderClass, conf);
    if (azureTokenProvider == null) {
      throw new IllegalArgumentException("Failed to initialize " + className);
    }

    azureTokenProvider.initialize(conf);
    return azureTokenProvider;
  }

  private AccessTokenProvider getAccessTokenProvider(Configuration conf)
      throws IOException {
    TokenProviderType type = conf.getEnum(
        AdlConfKeys.AZURE_AD_TOKEN_PROVIDER_TYPE_KEY, TokenProviderType.Custom);

    switch (type) {
    case RefreshToken:
      tokenProvider = getConfRefreshTokenBasedTokenProvider(conf);
      break;
    case ClientCredential:
      tokenProvider = getConfCredentialBasedTokenProvider(conf);
      break;
    case Custom:
    default:
      AzureADTokenProvider azureADTokenProvider = getCustomAccessTokenProvider(
          conf);
      tokenProvider = new SdkTokenProviderAdapter(azureADTokenProvider);
      break;
    }

    return tokenProvider;
  }

  private AccessTokenProvider getConfCredentialBasedTokenProvider(
      Configuration conf) {
    String clientId = getNonEmptyVal(conf, AZURE_AD_CLIENT_ID_KEY);
    String refreshUrl = getNonEmptyVal(conf, AZURE_AD_REFRESH_URL_KEY);
    String clientSecret = getNonEmptyVal(conf, AZURE_AD_CLIENT_SECRET_KEY);
    return new ClientCredsTokenProvider(refreshUrl, clientId, clientSecret);
  }

  private AccessTokenProvider getConfRefreshTokenBasedTokenProvider(
      Configuration conf) {
    String clientId = getNonEmptyVal(conf, AZURE_AD_CLIENT_ID_KEY);
    String refreshToken = getNonEmptyVal(conf, AZURE_AD_REFRESH_TOKEN_KEY);
    return new RefreshTokenBasedTokenProvider(clientId, refreshToken);
  }

  @VisibleForTesting
  AccessTokenProvider getTokenProvider() {
    return tokenProvider;
  }

  @VisibleForTesting
  AzureADTokenProvider getAzureTokenProvider() {
    return azureTokenProvider;
  }

  /**
   * Constructing home directory locally is fine as long as Hadoop
   * local user name and ADL user name relationship story is not fully baked
   * yet.
   *
   * @return Hadoop local user home directory.
   */
  @Override
  public Path getHomeDirectory() {
    return makeQualified(new Path("/user/" + userName));
  }

  /**
   * Create call semantic is handled differently in case of ADL. Create
   * semantics is translated to Create/Append
   * semantics.
   * 1. No dedicated connection to server.
   * 2. Buffering is locally done, Once buffer is full or flush is invoked on
   * the by the caller. All the pending
   * data is pushed to ADL as APPEND operation code.
   * 3. On close - Additional call is send to server to close the stream, and
   * release lock from the stream.
   *
   * Necessity of Create/Append semantics is
   * 1. ADL backend server does not allow idle connection for longer duration
   * . In case of slow writer scenario,
   * observed connection timeout/Connection reset causing occasional job
   * failures.
   * 2. Performance boost to jobs which are slow writer, avoided network latency
   * 3. ADL equally better performing with multiple of 4MB chunk as append
   * calls.
   *
   * @param f           File path
   * @param permission  Access permission for the newly created file
   * @param overwrite   Remove existing file and recreate new one if true
   *                    otherwise throw error if file exist
   * @param bufferSize  Buffer size, ADL backend does not honour
   * @param replication Replication count, ADL backend does not honour
   * @param blockSize   Block size, ADL backend does not honour
   * @param progress    Progress indicator
   * @return FSDataOutputStream OutputStream on which application can push
   * stream of bytes
   * @throws IOException when system error, internal server error or user error
   */
  @Override
  public FSDataOutputStream create(Path f, FsPermission permission,
      boolean overwrite, int bufferSize, short replication, long blockSize,
      Progressable progress) throws IOException {
    statistics.incrementWriteOps(1);
    IfExists overwriteRule = overwrite ? IfExists.OVERWRITE : IfExists.FAIL;
    return new FSDataOutputStream(new AdlFsOutputStream(adlClient
        .createFile(toRelativeFilePath(f), overwriteRule,
            Integer.toOctalString(applyUMask(permission).toShort()), true),
        getConf()), this.statistics);
  }

  /**
   * Opens an FSDataOutputStream at the indicated Path with write-progress
   * reporting. Same as create(), except fails if parent directory doesn't
   * already exist.
   *
   * @param f           the file name to open
   * @param permission  Access permission for the newly created file
   * @param flags       {@link CreateFlag}s to use for this stream.
   * @param bufferSize  the size of the buffer to be used. ADL backend does
   *                    not honour
   * @param replication required block replication for the file. ADL backend
   *                    does not honour
   * @param blockSize   Block size, ADL backend does not honour
   * @param progress    Progress indicator
   * @throws IOException when system error, internal server error or user error
   * @see #setPermission(Path, FsPermission)
   * @deprecated API only for 0.20-append
   */
  @Override
  public FSDataOutputStream createNonRecursive(Path f, FsPermission permission,
      EnumSet<CreateFlag> flags, int bufferSize, short replication,
      long blockSize, Progressable progress) throws IOException {
    statistics.incrementWriteOps(1);
    IfExists overwriteRule = IfExists.FAIL;
    for (CreateFlag flag : flags) {
      if (flag == CreateFlag.OVERWRITE) {
        overwriteRule = IfExists.OVERWRITE;
        break;
      }
    }

    return new FSDataOutputStream(new AdlFsOutputStream(adlClient
        .createFile(toRelativeFilePath(f), overwriteRule,
            Integer.toOctalString(applyUMask(permission).toShort()), false),
        getConf()), this.statistics);
  }

  /**
   * Append to an existing file (optional operation).
   *
   * @param f          the existing file to be appended.
   * @param bufferSize the size of the buffer to be used. ADL backend does
   *                   not honour
   * @param progress   Progress indicator
   * @throws IOException when system error, internal server error or user error
   */
  @Override
  public FSDataOutputStream append(Path f, int bufferSize,
      Progressable progress) throws IOException {
    statistics.incrementWriteOps(1);
    return new FSDataOutputStream(
        new AdlFsOutputStream(adlClient.getAppendStream(toRelativeFilePath(f)),
            getConf()), this.statistics);
  }

  /**
   * Azure data lake does not support user configuration for data replication
   * hence not leaving system to query on
   * azure data lake.
   *
   * Stub implementation
   *
   * @param p           Not honoured
   * @param replication Not honoured
   * @return True hard coded since ADL file system does not support
   * replication configuration
   * @throws IOException No exception would not thrown in this case however
   *                     aligning with parent api definition.
   */
  @Override
  public boolean setReplication(final Path p, final short replication)
      throws IOException {
    statistics.incrementWriteOps(1);
    return true;
  }

  /**
   * Open call semantic is handled differently in case of ADL. Instead of
   * network stream is returned to the user,
   * Overridden FsInputStream is returned.
   *
   * @param f          File path
   * @param buffersize Buffer size, Not honoured
   * @return FSDataInputStream InputStream on which application can read
   * stream of bytes
   * @throws IOException when system error, internal server error or user error
   */
  @Override
  public FSDataInputStream open(final Path f, final int buffersize)
      throws IOException {
    statistics.incrementReadOps(1);
    return new FSDataInputStream(
        new AdlFsInputStream(adlClient.getReadStream(toRelativeFilePath(f)),
            statistics, getConf()));
  }

  /**
   * Return a file status object that represents the path.
   *
   * @param f The path we want information from
   * @return a FileStatus object
   * @throws IOException when the path does not exist or any other error;
   *                     IOException see specific implementation
   */
  @Override
  public FileStatus getFileStatus(final Path f) throws IOException {
    statistics.incrementReadOps(1);
    DirectoryEntry entry = adlClient.getDirectoryEntry(toRelativeFilePath(f));
    return toFileStatus(entry, f);
  }

  /**
   * List the statuses of the files/directories in the given path if the path is
   * a directory.
   *
   * @param f given path
   * @return the statuses of the files/directories in the given patch
   * @throws IOException when the path does not exist or any other error;
   *                     IOException see specific implementation
   */
  @Override
  public FileStatus[] listStatus(final Path f) throws IOException {
    statistics.incrementReadOps(1);
    List<DirectoryEntry> entries =
        adlClient.enumerateDirectory(toRelativeFilePath(f));
    return toFileStatuses(entries, f);
  }

  /**
   * Renames Path src to Path dst.  Can take place on local fs
   * or remote DFS.
   *
   * ADLS support POSIX standard for rename operation.
   *
   * @param src path to be renamed
   * @param dst new path after rename
   * @return true if rename is successful
   * @throws IOException on failure
   */
  @Override
  public boolean rename(final Path src, final Path dst) throws IOException {
    statistics.incrementWriteOps(1);
    if (toRelativeFilePath(src).equals("/")) {
      return false;
    }

    return adlClient.rename(toRelativeFilePath(src), toRelativeFilePath(dst));
  }

  @Override
  @Deprecated
  public void rename(final Path src, final Path dst,
      final Options.Rename... options) throws IOException {
    statistics.incrementWriteOps(1);
    boolean overwrite = false;
    for (Rename renameOption : options) {
      if (renameOption == Rename.OVERWRITE) {
        overwrite = true;
        break;
      }
    }
    adlClient
        .rename(toRelativeFilePath(src), toRelativeFilePath(dst), overwrite);
  }

  /**
   * Concat existing files together.
   *
   * @param trg  the path to the target destination.
   * @param srcs the paths to the sources to use for the concatenation.
   * @throws IOException when system error, internal server error or user error
   */
  @Override
  public void concat(final Path trg, final Path[] srcs) throws IOException {
    statistics.incrementWriteOps(1);
    List<String> sourcesList = new ArrayList<String>();
    for (Path entry : srcs) {
      sourcesList.add(toRelativeFilePath(entry));
    }
    adlClient.concatenateFiles(toRelativeFilePath(trg), sourcesList);
  }

  /**
   * Delete a file.
   *
   * @param path      the path to delete.
   * @param recursive if path is a directory and set to
   *                  true, the directory is deleted else throws an exception.
   *                  In case of a file the recursive can be set to either
   *                  true or false.
   * @return true if delete is successful else false.
   * @throws IOException when system error, internal server error or user error
   */
  @Override
  public boolean delete(final Path path, final boolean recursive)
      throws IOException {
    statistics.incrementWriteOps(1);
    String relativePath = toRelativeFilePath(path);
    // Delete on root directory not supported.
    if (relativePath.equals("/")) {
      // This is important check after recent commit
      // HADOOP-12977 and HADOOP-13716 validates on root for
      // 1. if root is empty and non recursive delete then return false.
      // 2. if root is non empty and non recursive delete then throw exception.
      if (!recursive
          && adlClient.enumerateDirectory(toRelativeFilePath(path), 1).size()
          > 0) {
        throw new IOException("Delete on root is not supported.");
      }
      return false;
    }

    return recursive ?
        adlClient.deleteRecursive(relativePath) :
        adlClient.delete(relativePath);
  }

  /**
   * Make the given file and all non-existent parents into
   * directories. Has the semantics of Unix 'mkdir -p'.
   * Existence of the directory hierarchy is not an error.
   *
   * @param path       path to create
   * @param permission to apply to path
   */
  @Override
  public boolean mkdirs(final Path path, final FsPermission permission)
      throws IOException {
    statistics.incrementWriteOps(1);
    return adlClient.createDirectory(toRelativeFilePath(path),
        Integer.toOctalString(applyUMask(permission).toShort()));
  }

  private FileStatus[] toFileStatuses(final List<DirectoryEntry> entries,
      final Path parent) {
    FileStatus[] fileStatuses = new FileStatus[entries.size()];
    int index = 0;
    for (DirectoryEntry entry : entries) {
      FileStatus status = toFileStatus(entry, parent);
      if (!(entry.name == null || entry.name == "")) {
        status.setPath(
            new Path(parent.makeQualified(uri, workingDirectory), entry.name));
      }

      fileStatuses[index++] = status;
    }

    return fileStatuses;
  }

  private FsPermission applyUMask(FsPermission permission) {
    if (permission == null) {
      permission = FsPermission.getDefault();
    }
    return permission.applyUMask(FsPermission.getUMask(getConf()));
  }

  private FileStatus toFileStatus(final DirectoryEntry entry, final Path f) {
    boolean isDirectory = entry.type == DirectoryEntryType.DIRECTORY;
    long lastModificationData = entry.lastModifiedTime.getTime();
    long lastAccessTime = entry.lastAccessTime.getTime();
    FsPermission permission = new AdlPermission(aclBitStatus,
        Short.valueOf(entry.permission, 8));
    String user = entry.user;
    String group = entry.group;

    FileStatus status;
    if (overrideOwner) {
      status = new FileStatus(entry.length, isDirectory, ADL_REPLICATION_FACTOR,
          ADL_BLOCK_SIZE, lastModificationData, lastAccessTime, permission,
          userName, "hdfs", this.makeQualified(f));
    } else {
      status = new FileStatus(entry.length, isDirectory, ADL_REPLICATION_FACTOR,
          ADL_BLOCK_SIZE, lastModificationData, lastAccessTime, permission,
          user, group, this.makeQualified(f));
    }

    return status;
  }

  /**
   * Set owner of a path (i.e. a file or a directory).
   * The parameters owner and group cannot both be null.
   *
   * @param path  The path
   * @param owner If it is null, the original username remains unchanged.
   * @param group If it is null, the original groupname remains unchanged.
   */
  @Override
  public void setOwner(final Path path, final String owner, final String group)
      throws IOException {
    statistics.incrementWriteOps(1);
    adlClient.setOwner(toRelativeFilePath(path), owner, group);
  }

  /**
   * Set permission of a path.
   *
   * @param path       The path
   * @param permission Access permission
   */
  @Override
  public void setPermission(final Path path, final FsPermission permission)
      throws IOException {
    statistics.incrementWriteOps(1);
    adlClient.setPermission(toRelativeFilePath(path),
        Integer.toOctalString(permission.toShort()));
  }

  /**
   * Modifies ACL entries of files and directories.  This method can add new ACL
   * entries or modify the permissions on existing ACL entries.  All existing
   * ACL entries that are not specified in this call are retained without
   * changes.  (Modifications are merged into the current ACL.)
   *
   * @param path    Path to modify
   * @param aclSpec List of AclEntry describing modifications
   * @throws IOException if an ACL could not be modified
   */
  @Override
  public void modifyAclEntries(final Path path, final List<AclEntry> aclSpec)
      throws IOException {
    statistics.incrementWriteOps(1);
    List<com.microsoft.azure.datalake.store.acl.AclEntry> msAclEntries = new
        ArrayList<com.microsoft.azure.datalake.store.acl.AclEntry>();
    for (AclEntry aclEntry : aclSpec) {
      msAclEntries.add(com.microsoft.azure.datalake.store.acl.AclEntry
          .parseAclEntry(aclEntry.toString()));
    }
    adlClient.modifyAclEntries(toRelativeFilePath(path), msAclEntries);
  }

  /**
   * Removes ACL entries from files and directories.  Other ACL entries are
   * retained.
   *
   * @param path    Path to modify
   * @param aclSpec List of AclEntry describing entries to remove
   * @throws IOException if an ACL could not be modified
   */
  @Override
  public void removeAclEntries(final Path path, final List<AclEntry> aclSpec)
      throws IOException {
    statistics.incrementWriteOps(1);
    List<com.microsoft.azure.datalake.store.acl.AclEntry> msAclEntries = new
        ArrayList<com.microsoft.azure.datalake.store.acl.AclEntry>();
    for (AclEntry aclEntry : aclSpec) {
      msAclEntries.add(com.microsoft.azure.datalake.store.acl.AclEntry
          .parseAclEntry(aclEntry.toString(), true));
    }
    adlClient.removeAclEntries(toRelativeFilePath(path), msAclEntries);
  }

  /**
   * Removes all default ACL entries from files and directories.
   *
   * @param path Path to modify
   * @throws IOException if an ACL could not be modified
   */
  @Override
  public void removeDefaultAcl(final Path path) throws IOException {
    statistics.incrementWriteOps(1);
    adlClient.removeDefaultAcls(toRelativeFilePath(path));
  }

  /**
   * Removes all but the base ACL entries of files and directories.  The entries
   * for user, group, and others are retained for compatibility with permission
   * bits.
   *
   * @param path Path to modify
   * @throws IOException if an ACL could not be removed
   */
  @Override
  public void removeAcl(final Path path) throws IOException {
    statistics.incrementWriteOps(1);
    adlClient.removeAllAcls(toRelativeFilePath(path));
  }

  /**
   * Fully replaces ACL of files and directories, discarding all existing
   * entries.
   *
   * @param path    Path to modify
   * @param aclSpec List of AclEntry describing modifications, must include
   *                entries for user, group, and others for compatibility with
   *                permission bits.
   * @throws IOException if an ACL could not be modified
   */
  @Override
  public void setAcl(final Path path, final List<AclEntry> aclSpec)
      throws IOException {
    statistics.incrementWriteOps(1);
    List<com.microsoft.azure.datalake.store.acl.AclEntry> msAclEntries = new
        ArrayList<com.microsoft.azure.datalake.store.acl.AclEntry>();
    for (AclEntry aclEntry : aclSpec) {
      msAclEntries.add(com.microsoft.azure.datalake.store.acl.AclEntry
          .parseAclEntry(aclEntry.toString()));
    }

    adlClient.setAcl(toRelativeFilePath(path), msAclEntries);
  }

  /**
   * Gets the ACL of a file or directory.
   *
   * @param path Path to get
   * @return AclStatus describing the ACL of the file or directory
   * @throws IOException if an ACL could not be read
   */
  @Override
  public AclStatus getAclStatus(final Path path) throws IOException {
    statistics.incrementReadOps(1);
    com.microsoft.azure.datalake.store.acl.AclStatus adlStatus = adlClient
        .getAclStatus(toRelativeFilePath(path));
    AclStatus.Builder aclStatusBuilder = new AclStatus.Builder();
    aclStatusBuilder.owner(adlStatus.owner);
    aclStatusBuilder.group(adlStatus.group);
    aclStatusBuilder.setPermission(
        new FsPermission(Short.valueOf(adlStatus.octalPermissions, 8)));
    aclStatusBuilder.stickyBit(adlStatus.stickyBit);
    String aclListString = com.microsoft.azure.datalake.store.acl.AclEntry
        .aclListToString(adlStatus.aclSpec);
    List<AclEntry> aclEntries = AclEntry.parseAclSpec(aclListString, true);
    aclStatusBuilder.addEntries(aclEntries);
    return aclStatusBuilder.build();
  }

  /**
   * Checks if the user can access a path.  The mode specifies which access
   * checks to perform.  If the requested permissions are granted, then the
   * method returns normally.  If access is denied, then the method throws an
   * {@link AccessControlException}.
   *
   * @param path Path to check
   * @param mode type of access to check
   * @throws AccessControlException        if access is denied
   * @throws java.io.FileNotFoundException if the path does not exist
   * @throws IOException                   see specific implementation
   */
  @Override
  public void access(final Path path, FsAction mode) throws IOException {
    statistics.incrementReadOps(1);
    if (!adlClient.checkAccess(toRelativeFilePath(path), mode.SYMBOL)) {
      throw new AccessControlException("Access Denied : " + path.toString());
    }
  }

  /**
   * Return the {@link ContentSummary} of a given {@link Path}.
   *
   * @param f path to use
   */
  @Override
  public ContentSummary getContentSummary(Path f) throws IOException {
    statistics.incrementReadOps(1);
    com.microsoft.azure.datalake.store.ContentSummary msSummary = adlClient
        .getContentSummary(toRelativeFilePath(f));
    return new ContentSummary(msSummary.length, msSummary.fileCount, msSummary.directoryCount, -1L,
        msSummary.spaceConsumed, -1L);
  }

  @VisibleForTesting
  protected String getTransportScheme() {
    return SECURE_TRANSPORT_SCHEME;
  }

  @VisibleForTesting
  String toRelativeFilePath(Path path) {
    return path.makeQualified(uri, workingDirectory).toUri().getPath();
  }

  /**
   * Get the current working directory for the given file system.
   *
   * @return the directory pathname
   */
  @Override
  public Path getWorkingDirectory() {
    return workingDirectory;
  }

  /**
   * Set the current working directory for the given file system. All relative
   * paths will be resolved relative to it.
   *
   * @param dir Working directory path.
   */
  @Override
  public void setWorkingDirectory(final Path dir) {
    if (dir == null) {
      throw new InvalidPathException("Working directory cannot be set to NULL");
    }

    /**
     * Do not validate the scheme and URI of the passsed parameter. When Adls
     * runs as additional file system, working directory set has the default
     * file system scheme and uri.
     *
     * Found a problem during PIG execution in
     * https://github.com/apache/pig/blob/branch-0
     * .15/src/org/apache/pig/backend/hadoop/executionengine/mapReduceLayer
     * /PigInputFormat.java#L235
     * However similar problem would be present in other application so
     * defaulting to build working directory using relative path only.
     */
    this.workingDirectory = this.makeAbsolute(dir);
  }

  /**
   * Return the number of bytes that large input files should be optimally
   * be split into to minimize i/o time.
   *
   * @deprecated use {@link #getDefaultBlockSize(Path)} instead
   */
  @Deprecated
  public long getDefaultBlockSize() {
    return ADL_BLOCK_SIZE;
  }

  /**
   * Return the number of bytes that large input files should be optimally
   * be split into to minimize i/o time.  The given path will be used to
   * locate the actual filesystem.  The full path does not have to exist.
   *
   * @param f path of file
   * @return the default block size for the path's filesystem
   */
  public long getDefaultBlockSize(Path f) {
    return getDefaultBlockSize();
  }

  /**
   * Get the block size.
   * @param f the filename
   * @return the number of bytes in a block
   */
  /**
   * @deprecated Use getFileStatus() instead
   */
  @Deprecated
  public long getBlockSize(Path f) throws IOException {
    return ADL_BLOCK_SIZE;
  }

  @Override
  public BlockLocation[] getFileBlockLocations(final FileStatus status,
      final long offset, final long length) throws IOException {
    if (status == null) {
      return null;
    }

    if ((offset < 0) || (length < 0)) {
      throw new IllegalArgumentException("Invalid start or len parameter");
    }

    if (status.getLen() < offset) {
      return new BlockLocation[0];
    }

    final String[] name = {"localhost"};
    final String[] host = {"localhost"};
    long blockSize = ADL_BLOCK_SIZE;
    int numberOfLocations =
        (int) (length / blockSize) + ((length % blockSize == 0) ? 0 : 1);
    BlockLocation[] locations = new BlockLocation[numberOfLocations];
    for (int i = 0; i < locations.length; i++) {
      long currentOffset = offset + (i * blockSize);
      long currentLength = Math.min(blockSize, offset + length - currentOffset);
      locations[i] = new BlockLocation(name, host, currentOffset,
          currentLength);
    }

    return locations;
  }

  @Override
  public BlockLocation[] getFileBlockLocations(final Path p, final long offset,
      final long length) throws IOException {
    // read ops incremented in getFileStatus
    FileStatus fileStatus = getFileStatus(p);
    return getFileBlockLocations(fileStatus, offset, length);
  }

  /**
   * Get replication.
   *
   * @param src file name
   * @return file replication
   * @deprecated Use getFileStatus() instead
   */
  @Deprecated
  public short getReplication(Path src) {
    return ADL_REPLICATION_FACTOR;
  }

  private Path makeAbsolute(Path path) {
    return path.isAbsolute() ? path : new Path(this.workingDirectory, path);
  }

  private static String getNonEmptyVal(Configuration conf, String key) {
    String value = conf.get(key);
    if (StringUtils.isEmpty(value)) {
      throw new IllegalArgumentException(
          "No value for " + key + " found in conf file.");
    }
    return value;
  }

}
