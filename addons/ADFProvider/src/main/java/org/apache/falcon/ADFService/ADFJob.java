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

package org.apache.falcon.ADFService;

import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.falcon.ADFService.util.ADFJsonConstants;
import org.apache.falcon.FalconException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Azure ADF base job.
 */
public abstract class ADFJob {

    // name prefix for all adf related entity, e.g. an adf hive process and the feeds associated with it
    public static final String ADF_ENTITY_NAME_PREFIX = "ADF_";
    // name prefix for all adf related job entity, i.e. adf hive/pig process and replication feed
    public static final String ADF_JOB_ENTITY_NAME_PREFIX = ADF_ENTITY_NAME_PREFIX + "JOB_";
    public static final int ADF_ENTITY_NAME_PREFIX_LENGTH = ADF_ENTITY_NAME_PREFIX.length();

    public static boolean isADFEntity(String entityName) {
        return entityName.startsWith(ADF_ENTITY_NAME_PREFIX);
    }

    public static boolean isADFJobEntity(String entityName) {
        return entityName.startsWith(ADF_JOB_ENTITY_NAME_PREFIX);
    }

    public static String getSessionID(String entityName) throws FalconException {
        if (!isADFJobEntity(entityName)) {
            throw new FalconException("The entity, " + entityName + ", is not an ADF Job Entity.");
        }
        return entityName.substring(ADF_ENTITY_NAME_PREFIX_LENGTH);
    }

    public static enum JobType {
        HIVE, PIG, REPLICATE
    }

    private static enum RequestType {
        HADOOPREPLICATEDATA, HADOOPHIVE, HADOOPPIG
    }

    private static JobType getJobType(String msg) throws FalconException {
        try {
            JSONObject obj = new JSONObject(msg);
            JSONObject activity = obj.getJSONObject(ADFJsonConstants.ADF_REQUEST_ACTIVITY);
            if (activity == null) {
                throw new FalconException("JSON object " + ADFJsonConstants.ADF_REQUEST_ACTIVITY + " not found in ADF"
                        + " request.");
            }

            JSONObject activityProperties = activity.getJSONObject(ADFJsonConstants.ADF_REQUEST_TRANSFORMATION);
            if (activityProperties == null) {
                throw new FalconException("JSON object " + ADFJsonConstants.ADF_REQUEST_TRANSFORMATION + " not found "
                        + "in ADF request.");
            }

            String type = activityProperties.getString(ADFJsonConstants.ADF_REQUEST_TYPE);
            if (StringUtils.isBlank(type)) {
                throw new FalconException(ADFJsonConstants.ADF_REQUEST_TYPE + " not found in ADF request msg");
            }

            switch (RequestType.valueOf(type.toUpperCase())) {
            case HADOOPREPLICATEDATA:
                return JobType.REPLICATE;
            case HADOOPHIVE:
                return JobType.HIVE;
            case HADOOPPIG:
                return JobType.PIG;
            default:
                throw new FalconException("Unrecognized ADF job type: " + type);
            }
        } catch (JSONException e) {
            throw new FalconException("Error when parsing ADF JSON message: " + msg, e);
        }
    }

    public abstract void submitJob();

    protected JSONObject message;
    protected String id;
    protected JobType type;
    protected String startTime, endTime;
    protected String frequency;

    private Map<String, JSONObject> linkedServicesMap = new HashMap<String, JSONObject>();
    private Map<String, JSONObject> tablesMap = new HashMap<String, JSONObject>();

    public ADFJob(String msg) throws FalconException {
        try {
            message = new JSONObject(msg);
            id = message.getString(ADFJsonConstants.ADF_REQUEST_JOBID);
            if (StringUtils.isBlank(id)) {
                throw new FalconException(ADFJsonConstants.ADF_REQUEST_JOBID + " not found in ADF request");
            }

            startTime = message.getString(ADFJsonConstants.ADF_REQUEST_START_TIME);
            endTime = message.getString(ADFJsonConstants.ADF_REQUEST_END_TIME);

            JSONObject scheduler = message.getJSONObject(ADFJsonConstants.ADF_REQUEST_SCHEDULER);
            frequency = scheduler.getString(ADFJsonConstants.ADF_REQUEST_FREQUENCY).toLowerCase() + "s("
                    + scheduler.getInt(ADFJsonConstants.ADF_REQUEST_INTERVAL) + ")";

            JSONArray linkedServices = message.getJSONArray(ADFJsonConstants.ADF_REQUEST_LINKED_SERVICES);
            for (int i = 0; i < linkedServices.length(); i++) {
                JSONObject linkedService = linkedServices.getJSONObject(i);
                linkedServicesMap.put(linkedService.getString(ADFJsonConstants.ADF_REQUEST_NAME), linkedService);
            }

            JSONArray tables = message.getJSONArray(ADFJsonConstants.ADF_REQUEST_TABLES);
            for (int i = 0; i < tables.length(); i++) {
                JSONObject table = tables.getJSONObject(i);
                tablesMap.put(table.getString(ADFJsonConstants.ADF_REQUEST_NAME), table);
            }
        } catch (JSONException e) {
            throw new FalconException("Error when parsing ADF JSON message: " + msg, e);
        }
    }

    public String jobEntityName() {
        return ADF_JOB_ENTITY_NAME_PREFIX + id;
    }

    public String sessionID() {
        return id;
    }

    public JobType jobType() {
        return type;
    }

    protected String getClusterName(String linkedServiceName) {
        JSONObject linkedService = linkedServicesMap.get(linkedServiceName);
        if (linkedService == null) {
            return null;
        }

        try {
            return linkedService.getJSONObject(ADFJsonConstants.ADF_REQUEST_PROPERTIES)
                    .getJSONObject(ADFJsonConstants.ADF_REQUEST_EXTENDED_PROPERTIES)
                    .getString(ADFJsonConstants.ADF_REQUEST_CLUSTER_NAME);
        } catch (JSONException e) {
            return null;
        }
    }

    protected String getTablePath(String tableName) {
        JSONObject table = tablesMap.get(tableName);
        if (table == null) {
            return null;
        }

        try {
            return table.getJSONObject(ADFJsonConstants.ADF_REQUEST_PROPERTIES)
                    .getJSONObject(ADFJsonConstants.ADF_REQUEST_LOCATION)
                    .getJSONObject(ADFJsonConstants.ADF_REQUEST_EXTENDED_PROPERTIES)
                    .getString(ADFJsonConstants.ADF_REQUEST_FOLDER_PATH);
        } catch (JSONException e) {
            return null;
        }
    }

    protected String getTableCluster(String tableName) {
        JSONObject table = tablesMap.get(tableName);
        if (table == null) {
            return null;
        }

        try {
            String linkedServiceName = table.getJSONObject(ADFJsonConstants.ADF_REQUEST_PROPERTIES)
                    .getJSONObject(ADFJsonConstants.ADF_REQUEST_LOCATION)
                    .getString(ADFJsonConstants.ADF_REQUEST_LINKED_SERVICE_NAME);
            return getClusterName(linkedServiceName);
        } catch (JSONException e) {
            return null;
        }
    }
}
