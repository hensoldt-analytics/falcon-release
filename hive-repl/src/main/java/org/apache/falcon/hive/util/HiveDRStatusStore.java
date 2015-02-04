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

package org.apache.falcon.hive.util;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.falcon.hive.exception.HiveReplicationException;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class HiveDRStatusStore extends DRStatusStore {

    private static final Logger LOG = LoggerFactory.getLogger(DRStatusStore.class);
    private FileSystem fileSystem;

    private static final String DEFAULT_STORE_PATH = BASE_DEFAULT_STORE_PATH + "hiveReplicationStatusStore/";
    private static final FsPermission DEFAULT_STORE_PERMISSION =
            new FsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL);
    private static final FsPermission DEFAULT_STATUS_DIR_PERMISSION =
            new FsPermission(FsAction.ALL, FsAction.READ_EXECUTE, FsAction.READ_EXECUTE);

    private static final String LATEST_FILE = "latest.json";
    private static final int FILE_ROTATION_LIMIT = 10;
    private static final int FILE_ROTATION_TIME = 86400000; // 1 day


    public HiveDRStatusStore(FileSystem targetFileSystem) throws IOException {
        this.fileSystem = targetFileSystem;
        Path basePath = new Path(BASE_DEFAULT_STORE_PATH);
        if (fileSystem.exists(basePath)) {
            if (!fileSystem.getFileStatus(basePath).getPermission().equals(DEFAULT_STORE_PERMISSION)) {
                throw new IOException("Base dir " + BASE_DEFAULT_STORE_PATH + "does not have correct permissions. "
                        + "Please set to 777");
            }
        } else {
            throw new IOException("Please create base dir " + BASE_DEFAULT_STORE_PATH + " with permission 777.");
        }

        Path storePath = new Path(DEFAULT_STORE_PATH);
        if (!fileSystem.exists(storePath)) {
            if (! FileSystem.mkdirs(fileSystem, storePath, DEFAULT_STORE_PERMISSION)) {
                throw new IOException("mkdir failed for " + DEFAULT_STORE_PATH);
            }
        } else {
            if (!fileSystem.getFileStatus(storePath).getPermission().equals(DEFAULT_STORE_PERMISSION)) {
                throw new IOException("Base dir " + DEFAULT_STORE_PATH + "does not have correct permissions. "
                        + "Please set to 777");
            }
        }
    }

     /**
        get all DB updated by the job. get all current table statuses for the DB merge the latest repl
        status with prev table repl statuses. If all are success, store the status as success with largest
        eventId for the DB else store status as failure for the DB and lowest eventId
     */
    @Override
    public void updateReplicationStatus(String jobName, List<ReplicationStatus> statusList)
            throws HiveReplicationException {

        Map<String, DBReplicationStatus> dbStatusMap = new HashMap<String, DBReplicationStatus>();
        for (ReplicationStatus status : statusList) {
            if (!status.getJobName().equals(jobName)) {
                String error = "JobName for status does not match current job \"" + jobName
                        + "\". Status is " + status.toJsonString();
                LOG.error(error);
                throw new HiveReplicationException(error);
            }

            // init dbStatusMap and tableStatusMap from existing statuses.
            if (! dbStatusMap.containsKey(status.getDatabase())) {
                DBReplicationStatus dbStatus = getDbReplicationStatus(status.getSourceUri(), status.getTargetUri(),
                        status.getJobName(), status.getDatabase());
                dbStatusMap.put(status.getDatabase(), dbStatus);
            }

            // update existing statuses with new status for db/tables
            if (StringUtils.isEmpty(status.getTable())) { // db level replication status.
                dbStatusMap.get(status.getDatabase()).updateDbStatus(status);
            } else { // table level replication status
                dbStatusMap.get(status.getDatabase()).updateTableStatus(status);
            }
        }
        // write to disk
        for (Map.Entry<String, DBReplicationStatus> entry : dbStatusMap.entrySet()) {
            writeStatusFile(entry.getValue());
        }
    }

    @Override
    public ReplicationStatus getReplicationStatus(String source, String target, String jobName, String database)
            throws HiveReplicationException {
        return getDbReplicationStatus(source, target, jobName, database).getDbReplicationStatus();
    }


    public ReplicationStatus getReplicationStatus(String source, String target,
                                                  String jobName, String database,
                                                  String table) throws HiveReplicationException {
        if (StringUtils.isEmpty(table)) {
            return getReplicationStatus(source, target, jobName, database);
        } else {
            DBReplicationStatus dbReplicationStatus = getDbReplicationStatus(source, target, jobName, database);
            if (dbReplicationStatus.getTableStatuses().containsKey(table)) {
                return dbReplicationStatus.getTableStatuses().get(table);
            }
            return new ReplicationStatus(source, target, jobName, database, table, ReplicationStatus.Status.INIT, -1);
        }
    }

    @Override
    public Iterator<ReplicationStatus> getTableReplicationStatusesInDb(String source, String target,
                                                                       String jobName, String database)
            throws HiveReplicationException {
        DBReplicationStatus dbReplicationStatus = getDbReplicationStatus(source, target, jobName, database);
        return dbReplicationStatus.getTableStatusIterator();
    }

    private DBReplicationStatus getDbReplicationStatus(String source, String target, String jobName,
                                                       String database) throws HiveReplicationException{
        DBReplicationStatus dbReplicationStatus = null;
        Path statusDirPath = getStatusDirPath(database, jobName);
        // todo check if database name or jobName can contain chars not allowed by hdfs dir/file naming.
        // if yes, use md5 of the same for dir names. prefer to use actual db names for readability.

        try {
            if (fileSystem.exists(statusDirPath)) {
                dbReplicationStatus = readStatusFile(statusDirPath);
            }
            if(null == dbReplicationStatus) {
                dbReplicationStatus = new DBReplicationStatus(new ReplicationStatus(source, target, jobName,
                        database, null, ReplicationStatus.Status.INIT, -1));
                if (!FileSystem.mkdirs(fileSystem, statusDirPath, DEFAULT_STATUS_DIR_PERMISSION)) {
                    String error = "mkdir failed for " + statusDirPath.toString();
                    LOG.error(error);
                    throw new HiveReplicationException(error);
                }
                writeStatusFile(dbReplicationStatus);
            }
            return dbReplicationStatus;
        } catch (IOException e) {
            String error = "Failed to get ReplicationStatus for job " + jobName;
            LOG.error(error);
            throw new HiveReplicationException(error);
        }
    }

    private Path getStatusDirPath(DBReplicationStatus dbReplicationStatus) {
        ReplicationStatus status = dbReplicationStatus.getDbReplicationStatus();
        return getStatusDirPath(status.getDatabase(), status.getJobName());
    }

    public Path getStatusDirPath(String database, String jobName) {
        return new Path(DEFAULT_STORE_PATH + "/" + database + "/" + jobName);
    }

    private void writeStatusFile(DBReplicationStatus dbReplicationStatus) throws HiveReplicationException {
        dbReplicationStatus.updateDbStatusFromTableStatuses();
        String statusDir = getStatusDirPath(dbReplicationStatus).toString();
        try {
            Path latestFile = new Path(statusDir + "/" + LATEST_FILE);
            if (fileSystem.exists(latestFile)) {
                Path renamedFile = new Path(statusDir + "/"
                        + String.valueOf(fileSystem.getFileStatus(latestFile).getModificationTime()) + ".json");
                fileSystem.rename(latestFile, renamedFile);
            }

            FSDataOutputStream stream = FileSystem.create(fileSystem, latestFile, DEFAULT_STATUS_DIR_PERMISSION);
            stream.write(dbReplicationStatus.toJsonString().getBytes());
            stream.close();

        } catch (IOException e) {
            String error = "Failed to write latest Replication status into dir " + statusDir;
            LOG.error(error);
            throw new HiveReplicationException(error);
        }

        rotateStatusFiles(new Path(statusDir), FILE_ROTATION_LIMIT, FILE_ROTATION_TIME);
    }

    public void rotateStatusFiles(Path statusDir, int numFiles, int maxFileAge) throws HiveReplicationException {

        List<String> fileList = new ArrayList<String>();
        long now = System.currentTimeMillis();
        try {
            RemoteIterator<LocatedFileStatus> fileIterator = fileSystem.listFiles(statusDir, false);
            while (fileIterator.hasNext()) {
                fileList.add(fileIterator.next().getPath().toString());
            }
            if (fileList.size() > (numFiles+1)) {
                // delete some files, as long as they are older than the time.
                Collections.sort(fileList);
                for (String file : fileList.subList(0, (fileList.size() - numFiles + 1))) {
                    long modTime = fileSystem.getFileStatus(new Path(file)).getModificationTime();
                    if ((now - modTime) > maxFileAge) {
                        Path deleteFilePath = new Path(file);
                        if (fileSystem.exists(deleteFilePath)) {
                            fileSystem.delete(deleteFilePath, false);
                        }
                    }
                }
            }
        } catch (IOException e) {
            String error = "Failed to rotate status files in dir " + statusDir.toString();
            LOG.error(error);
            throw new HiveReplicationException(error);
        }
    }

    private DBReplicationStatus readStatusFile(Path statusDirPath) throws HiveReplicationException {
        try {
            Path statusFile = new Path(statusDirPath.toString() + "/" + LATEST_FILE);
            if ((!fileSystem.exists(statusDirPath)) || (!fileSystem.exists(statusFile))) {
                return null;
            } else {
                return new DBReplicationStatus(IOUtils.toString(fileSystem.open(statusFile)));
            }
        } catch (IOException e) {
            String error = "Failed to read latest Replication status from dir " + statusDirPath.toString();
            LOG.error(error);
            throw new HiveReplicationException(error);
        }
    }

    @Override
    public void checkForReplicationConflict(String source, String target, String jobName,
                                            String database, String table) throws HiveReplicationException {
        // todo
    }

}
