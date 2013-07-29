// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InvalidObjectException;
import java.net.URL;
import java.net.URLConnection;
import java.rmi.NoSuchObjectException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.thrift.TBase;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.TableName;
import com.cloudera.impala.catalog.Db.TableLoadingException;
import com.cloudera.impala.catalog.FileFormat;
import com.cloudera.impala.catalog.RowFormat;
import com.cloudera.impala.common.ImpalaException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.thrift.TAlterTableAddPartitionParams;
import com.cloudera.impala.thrift.TAlterTableAddReplaceColsParams;
import com.cloudera.impala.thrift.TAlterTableChangeColParams;
import com.cloudera.impala.thrift.TAlterTableDropColParams;
import com.cloudera.impala.thrift.TAlterTableDropPartitionParams;
import com.cloudera.impala.thrift.TAlterTableParams;
import com.cloudera.impala.thrift.TAlterTableRenameParams;
import com.cloudera.impala.thrift.TAlterTableSetFileFormatParams;
import com.cloudera.impala.thrift.TAlterTableSetLocationParams;
import com.cloudera.impala.thrift.TCatalogUpdate;
import com.cloudera.impala.thrift.TClientRequest;
import com.cloudera.impala.thrift.TCreateDbParams;
import com.cloudera.impala.thrift.TCreateTableLikeParams;
import com.cloudera.impala.thrift.TCreateTableParams;
import com.cloudera.impala.thrift.TDescribeTableParams;
import com.cloudera.impala.thrift.TDescribeTableResult;
import com.cloudera.impala.thrift.TDropDbParams;
import com.cloudera.impala.thrift.TDropTableParams;
import com.cloudera.impala.thrift.TExecRequest;
import com.cloudera.impala.thrift.TGetDbsParams;
import com.cloudera.impala.thrift.TGetDbsResult;
import com.cloudera.impala.thrift.TGetTablesParams;
import com.cloudera.impala.thrift.TGetTablesResult;
import com.cloudera.impala.thrift.TMetadataOpRequest;
import com.cloudera.impala.thrift.TMetadataOpResponse;
import com.cloudera.impala.thrift.TPartitionKeyValue;

/**
 * JNI-callable interface onto a wrapped Frontend instance. The main point is to serialise
 * and deserialise thrift structures between C and Java.
 */
public class JniFrontend {
  private final static Logger LOG = LoggerFactory.getLogger(JniFrontend.class);

  private final static TBinaryProtocol.Factory protocolFactory =
      new TBinaryProtocol.Factory();

  private final Frontend frontend;

  public JniFrontend() {
    frontend = new Frontend();
  }

  public JniFrontend(boolean lazy) {
    frontend = new Frontend(lazy);
  }

  /**
   * Deserialized a serialized form of a Thrift data structure to its object form
   */
  private <T extends TBase> void deserializeThrift(T result, byte[] thriftData)
      throws ImpalaException {
    // TODO: avoid creating deserializer for each query?
    TDeserializer deserializer = new TDeserializer(protocolFactory);

    try {
      deserializer.deserialize(result, thriftData);
    } catch (TException e) {
      throw new InternalException(e.getMessage());
    }
  }

  /**
   * Jni wrapper for Frontend.createQueryExecRequest2(). Accepts a serialized
   * TClientRequest; returns a serialized TQueryExecRequest2.
   */
  public byte[] createExecRequest(byte[] thriftClientRequest)
      throws ImpalaException {
    TClientRequest request = new TClientRequest();
    deserializeThrift(request, thriftClientRequest);

    StringBuilder explainString = new StringBuilder();
    TExecRequest result = frontend.createExecRequest(request, explainString);
    LOG.info(explainString.toString());

    // TODO: avoid creating serializer for each query?
    TSerializer serializer = new TSerializer(protocolFactory);
    try {
      return serializer.serialize(result);
    } catch (TException e) {
      throw new InternalException(e.getMessage());
    }
  }

  public void alterTable(byte[] thriftAlterTableParams)
      throws ImpalaException, MetaException, org.apache.thrift.TException,
      InvalidObjectException, ImpalaException, TableLoadingException {
    TAlterTableParams params = new TAlterTableParams();
    deserializeThrift(params, thriftAlterTableParams);
    switch (params.getAlter_type()) {
      case ADD_REPLACE_COLUMNS:
        TAlterTableAddReplaceColsParams addReplaceColParams =
            params.getAdd_replace_cols_params();
        frontend.alterTableAddReplaceCols(TableName.fromThrift(params.getTable_name()),
            addReplaceColParams.getColumns(),
            addReplaceColParams.isReplace_existing_cols());
        break;
      case ADD_PARTITION:
        TAlterTableAddPartitionParams addPartParams = params.getAdd_partition_params();
        frontend.alterTableAddPartition(TableName.fromThrift(params.getTable_name()),
            addPartParams.getPartition_spec(), addPartParams.getLocation(),
            addPartParams.isIf_not_exists());
        break;
      case DROP_COLUMN:
        TAlterTableDropColParams dropColParams = params.getDrop_col_params();
        frontend.alterTableDropCol(TableName.fromThrift(params.getTable_name()),
            dropColParams.getCol_name());
        break;
      case CHANGE_COLUMN:
        TAlterTableChangeColParams changeColParams = params.getChange_col_params();
        frontend.alterTableChangeCol(TableName.fromThrift(params.getTable_name()),
            changeColParams.getCol_name(), changeColParams.getNew_col_def());
        break;
      case DROP_PARTITION:
        TAlterTableDropPartitionParams dropPartParams = params.getDrop_partition_params();
        frontend.alterTableDropPartition(TableName.fromThrift(params.getTable_name()),
            dropPartParams.getPartition_spec(), dropPartParams.isIf_exists());
        break;
      case RENAME_TABLE:
        TAlterTableRenameParams renameParams = params.getRename_params();
        frontend.alterTableRename(TableName.fromThrift(params.getTable_name()),
            TableName.fromThrift(renameParams.getNew_table_name()));
        break;
      case SET_FILE_FORMAT:
        TAlterTableSetFileFormatParams fileFormatParams =
            params.getSet_file_format_params();
        List<TPartitionKeyValue> fileFormatPartitionSpec = null;
        if (fileFormatParams.isSetPartition_spec()) {
          fileFormatPartitionSpec = fileFormatParams.getPartition_spec();
        }
        frontend.alterTableSetFileFormat(TableName.fromThrift(params.getTable_name()),
            fileFormatPartitionSpec,
            FileFormat.fromThrift(fileFormatParams.getFile_format()));
        break;
      case SET_LOCATION:
        TAlterTableSetLocationParams setLocationParams = params.getSet_location_params();
        List<TPartitionKeyValue> partitionSpec = null;
        if (setLocationParams.isSetPartition_spec()) {
          partitionSpec = setLocationParams.getPartition_spec();
        }
        frontend.alterTableSetLocation(TableName.fromThrift(params.getTable_name()),
            partitionSpec, setLocationParams.getLocation());
        break;
      default:
        throw new UnsupportedOperationException(
            "Unknown ALTER TABLE operation type: " + params.getAlter_type());
    }
  }

  public void createDatabase(byte[] thriftCreateDbParams)
      throws ImpalaException, MetaException, org.apache.thrift.TException,
      AlreadyExistsException, InvalidObjectException {
    TCreateDbParams params = new TCreateDbParams();
    deserializeThrift(params, thriftCreateDbParams);
    frontend.createDatabase(params.getDb(), params.getComment(), params.getLocation(),
        params.isIf_not_exists());
  }

  public void createTable(byte[] thriftCreateTableParams)
      throws ImpalaException, MetaException, NoSuchObjectException,
      org.apache.thrift.TException, AlreadyExistsException,
      InvalidObjectException {
    TCreateTableParams params = new TCreateTableParams();
    deserializeThrift(params, thriftCreateTableParams);
    frontend.createTable(TableName.fromThrift(params.getTable_name()),
        params.getColumns(), params.getPartition_columns(), params.getOwner(),
        params.isIs_external(), params.getComment(),
        RowFormat.fromThrift(params.getRow_format()),
        FileFormat.fromThrift(params.getFile_format()),
        params.getLocation(), params.isIf_not_exists());
  }

  public void createTableLike(byte[] thriftCreateTableLikeParams)
      throws ImpalaException, MetaException, NoSuchObjectException,
      org.apache.thrift.TException, AlreadyExistsException, InvalidObjectException,
      TableLoadingException {
    TCreateTableLikeParams params = new TCreateTableLikeParams();
    deserializeThrift(params, thriftCreateTableLikeParams);
    FileFormat fileFormat = null;
    if (params.isSetFile_format()) {
      fileFormat = FileFormat.fromThrift(params.getFile_format());
    }
    String comment = null;
    if (params.isSetComment()) {
      comment = params.getComment();
    }
    frontend.createTableLike(TableName.fromThrift(params.getTable_name()),
        TableName.fromThrift(params.getSrc_table_name()), params.getOwner(),
        params.isIs_external(), comment, fileFormat, params.getLocation(),
        params.isIf_not_exists());
  }

  public void dropDatabase(byte[] thriftDropDbParams)
      throws ImpalaException, MetaException, NoSuchObjectException,
      org.apache.thrift.TException, AlreadyExistsException, InvalidOperationException,
      InvalidObjectException {
    TDropDbParams params = new TDropDbParams();
    deserializeThrift(params, thriftDropDbParams);
    frontend.dropDatabase(params.getDb(), params.isIf_exists());
  }

  public void dropTable(byte[] thriftDropTableParams)
      throws ImpalaException, MetaException, NoSuchObjectException,
      org.apache.thrift.TException, AlreadyExistsException, InvalidOperationException,
      InvalidObjectException {
    TDropTableParams params = new TDropTableParams();
    deserializeThrift(params, thriftDropTableParams);
    frontend.dropTable(TableName.fromThrift(params.getTable_name()),
        params.isIf_exists());
  }

  /**
   * Return an explain plan based on thriftQueryRequest, a serialized TQueryRequest.
   * This call is thread-safe.
   */
  public String getExplainPlan(byte[] thriftQueryRequest) throws ImpalaException {
    TClientRequest request = new TClientRequest();
    deserializeThrift(request, thriftQueryRequest);
    String plan = frontend.getExplainString(request);
    LOG.info("Explain plan: " + plan);
    return plan;
  }

  /**
   * Process any updates to the metastore required after a query executes.
   * The argument is a serialized TCatalogUpdate.
   * @see Frontend#updateMetastore
   */
  public void updateMetastore(byte[] thriftCatalogUpdate) throws ImpalaException {
    TCatalogUpdate update = new TCatalogUpdate();
    deserializeThrift(update, thriftCatalogUpdate);
    frontend.updateMetastore(update);
  }

  /**
   * Returns a list of table names matching an optional pattern.
   * The argument is a serialized TGetTablesParams object.
   * The return type is a serialised TGetTablesResult object.
   * @see Frontend#getTableNames
   */
  public byte[] getTableNames(byte[] thriftGetTablesParams) throws ImpalaException {
    TGetTablesParams params = new TGetTablesParams();
    deserializeThrift(params, thriftGetTablesParams);
    List<String> tables = frontend.getTableNames(params.db, params.pattern);

    TGetTablesResult result = new TGetTablesResult();
    result.setTables(tables);

    TSerializer serializer = new TSerializer(protocolFactory);
    try {
      return serializer.serialize(result);
    } catch (TException e) {
      throw new InternalException(e.getMessage());
    }
  }

  /**
   * Returns a list of table names matching an optional pattern.
   * The argument is a serialized TGetTablesParams object.
   * The return type is a serialised TGetTablesResult object.
   * @see Frontend#getTableNames
   */
  public byte[] getDbNames(byte[] thriftGetTablesParams) throws ImpalaException {
    TGetDbsParams params = new TGetDbsParams();
    deserializeThrift(params, thriftGetTablesParams);
    List<String> dbs = frontend.getDbNames(params.pattern);

    TGetDbsResult result = new TGetDbsResult();
    result.setDbs(dbs);

    TSerializer serializer = new TSerializer(protocolFactory);
    try {
      return serializer.serialize(result);
    } catch (TException e) {
      throw new InternalException(e.getMessage());
    }
  }

  /**
   * Returns a list of the columns making up a table.
   * The argument is a serialized TDescribeTableParams object.
   * The return type is a serialised TDescribeTableResult object.
   * @see Frontend#describeTable
   */
  public byte[] describeTable(byte[] thriftDescribeTableParams) throws ImpalaException {
    TDescribeTableParams params = new TDescribeTableParams();
    deserializeThrift(params, thriftDescribeTableParams);
    TDescribeTableResult result = new TDescribeTableResult();
    result.setColumns(frontend.describeTable(params.getDb(), params.getTable_name()));

    TSerializer serializer = new TSerializer(protocolFactory);
    try {
      return serializer.serialize(result);
    } catch (TException e) {
      throw new InternalException(e.getMessage());
    }
  }

  /**
   * Executes a HiveServer2 metadata operation and returns a TMetadataOpResponse
   */
  public byte[] execHiveServer2MetadataOp(byte[] metadataOpsParams)
      throws ImpalaException {
    TMetadataOpRequest params = new TMetadataOpRequest();
    deserializeThrift(params, metadataOpsParams);
    TMetadataOpResponse result = frontend.execHiveServer2MetadataOp(params);

    TSerializer serializer = new TSerializer(protocolFactory);
    try {
      return serializer.serialize(result);
    } catch (TException e) {
      throw new InternalException(e.getMessage());
    }
  }

  // Caching this saves ~50ms per call to getHadoopConfigAsHtml
  private static final Configuration CONF = new Configuration();

  /**
   * Returns a string of all loaded Hadoop configuration parameters as a table of keys
   * and values. If asText is true, output in raw text. Otherwise, output in html.
   */
  public String getHadoopConfig(boolean asText) {
    StringBuilder output = new StringBuilder();
    if (asText) {
      output.append("Hadoop Configuration\n");
      // Write the set of files that make up the configuration
      output.append(CONF.toString());
      output.append("\n\n");
      // Write a table of key, value pairs
      for (Map.Entry<String, String> e : CONF) {
        output.append(e.getKey() + "=" + e.getValue() + "\n");
      }
      output.append("\n");
    } else {
      output.append("<h2>Hadoop Configuration</h2>");
      // Write the set of files that make up the configuration
      output.append(CONF.toString());
      output.append("\n\n");
      // Write a table of key, value pairs
      output.append("<table class='table table-bordered table-hover'>");
      output.append("<tr><th>Key</th><th>Value</th></tr>");
      for (Map.Entry<String, String> e : CONF) {
        output.append("<tr><td>" + e.getKey() + "</td><td>" + e.getValue() +
            "</td></tr>");
      }
      output.append("</table>");
    }
    return output.toString();
  }

  /**
   * Returns a string representation of a config value. If the config
   * can't be found, the empty string is returned. (This method is
   * called from JNI, and so NULL handling is easier to avoid.)
   */
  public String getHadoopConfigValue(String confName) {
    return CONF.get(confName, "");
  }

  public class CdhVersion implements Comparable<CdhVersion> {
    private final int major;
    private final int minor;

    public CdhVersion(String versionString) throws IllegalArgumentException {
      String[] version = versionString.split("\\.");
      if (version.length != 2) {
        throw new IllegalArgumentException("Invalid version string:" + versionString);
      }
      try {
        major = Integer.parseInt(version[0]);
        minor = Integer.parseInt(version[1]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid version string:" + versionString);
      }
    }

    public int compareTo(CdhVersion o) {
      return (this.major == o.major) ? (this.minor - o.minor) : (this.major - o.major);
    }

    @Override
    public String toString() {
      return major + "." + minor;
    }
  }

  /**
   * Returns an error string describing all configuration issues. If no config issues are
   * found, returns an empty string.
   * Checks are run only if Impala can determine that it is running on CDH.
   */
  public String checkHadoopConfig() {
    CdhVersion guessedCdhVersion = new CdhVersion("4.2"); //Always default to CDH 4.2
    StringBuilder output = new StringBuilder();


    return output.toString();
  }

  public void resetTable(String dbName, String tableName) throws ImpalaException {
    frontend.resetTable(dbName, tableName);
  }

  public void resetCatalog() {
    frontend.resetCatalog();
  }
}
