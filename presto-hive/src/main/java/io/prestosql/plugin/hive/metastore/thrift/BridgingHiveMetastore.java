/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.hive.metastore.thrift;

import com.google.common.collect.ImmutableMap;
import io.prestosql.plugin.hive.HivePartition;
import io.prestosql.plugin.hive.HiveType;
import io.prestosql.plugin.hive.PartitionStatistics;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.Database;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.plugin.hive.metastore.HivePrincipal;
import io.prestosql.plugin.hive.metastore.HivePrivilegeInfo;
import io.prestosql.plugin.hive.metastore.Partition;
import io.prestosql.plugin.hive.metastore.PartitionWithStatistics;
import io.prestosql.plugin.hive.metastore.PrincipalPrivileges;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.plugin.hive.util.HiveUtil;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.SchemaNotFoundException;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.security.RoleGrant;
import io.prestosql.spi.statistics.ColumnStatisticType;
import io.prestosql.spi.type.Type;
import org.apache.hadoop.hive.metastore.api.FieldSchema;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.plugin.hive.HiveMetadata.TABLE_COMMENT;
import static io.prestosql.plugin.hive.metastore.MetastoreUtil.isAvroTableWithSchemaSet;
import static io.prestosql.plugin.hive.metastore.MetastoreUtil.verifyCanDropColumn;
import static io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreUtil.fromMetastoreApiDatabase;
import static io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreUtil.fromMetastoreApiTable;
import static io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreUtil.isAvroTableWithSchemaSet;
import static io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreUtil.isCsvTable;
import static io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreUtil.toMetastoreApiDatabase;
import static io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreUtil.toMetastoreApiTable;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;
import static java.util.function.UnaryOperator.identity;

public class BridgingHiveMetastore
        implements HiveMetastore
{
    private final ThriftMetastore delegate;

    @Inject
    public BridgingHiveMetastore(ThriftMetastore delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public Optional<Database> getDatabase(String databaseName)
    {
        return delegate.getDatabase(databaseName).map(ThriftMetastoreUtil::fromMetastoreApiDatabase);
    }

    @Override
    public List<String> getAllDatabases()
    {
        return delegate.getAllDatabases();
    }

    @Override
    public Optional<Table> getTable(HiveIdentity identity, String databaseName, String tableName)
    {
        return delegate.getTable(identity, databaseName, tableName).map(table -> {
            if (isAvroTableWithSchemaSet(table) || isCsvTable(table)) {
                return fromMetastoreApiTable(table, delegate.getFields(identity, databaseName, tableName).get());
            }
            return fromMetastoreApiTable(table);
        });
    }

    @Override
    public Set<ColumnStatisticType> getSupportedColumnStatistics(Type type)
    {
        return delegate.getSupportedColumnStatistics(type);
    }

    @Override
    public PartitionStatistics getTableStatistics(HiveIdentity identity, Table table)
    {
        return delegate.getTableStatistics(identity, toMetastoreApiTable(table));
    }

    @Override
    public Map<String, PartitionStatistics> getPartitionStatistics(HiveIdentity identity, Table table, List<Partition> partitions)
    {
        return delegate.getPartitionStatistics(
                identity,
                toMetastoreApiTable(table),
                partitions.stream()
                        .map(ThriftMetastoreUtil::toMetastoreApiPartition)
                        .collect(toImmutableList()));
    }

    @Override
    public void updateTableStatistics(HiveIdentity identity, String databaseName, String tableName, Function<PartitionStatistics, PartitionStatistics> update)
    {
        delegate.updateTableStatistics(identity, databaseName, tableName, update);
    }

    @Override
    public void updatePartitionStatistics(HiveIdentity identity, Table table, String partitionName, Function<PartitionStatistics, PartitionStatistics> update)
    {
        delegate.updatePartitionStatistics(identity, toMetastoreApiTable(table), partitionName, update);
    }

    @Override
    public List<String> getAllTables(String databaseName)
    {
        return delegate.getAllTables(databaseName);
    }

    @Override
    public List<String> getTablesWithParameter(String databaseName, String parameterKey, String parameterValue)
    {
        return delegate.getTablesWithParameter(databaseName, parameterKey, parameterValue);
    }

    @Override
    public List<String> getAllViews(String databaseName)
    {
        return delegate.getAllViews(databaseName);
    }

    @Override
    public void createDatabase(HiveIdentity identity, Database database)
    {
        delegate.createDatabase(identity, toMetastoreApiDatabase(database));
    }

    @Override
    public void dropDatabase(HiveIdentity identity, String databaseName)
    {
        delegate.dropDatabase(identity, databaseName);
    }

    @Override
    public void renameDatabase(HiveIdentity identity, String databaseName, String newDatabaseName)
    {
        org.apache.hadoop.hive.metastore.api.Database database = delegate.getDatabase(databaseName)
                .orElseThrow(() -> new SchemaNotFoundException(databaseName));
        database.setName(newDatabaseName);
        delegate.alterDatabase(identity, databaseName, database);

        delegate.getDatabase(databaseName).ifPresent(newDatabase -> {
            if (newDatabase.getName().equals(databaseName)) {
                throw new PrestoException(NOT_SUPPORTED, "Hive metastore does not support renaming schemas");
            }
        });
    }

    @Override
    public void setDatabaseOwner(HiveIdentity identity, String databaseName, HivePrincipal principal)
    {
        Database database = fromMetastoreApiDatabase(delegate.getDatabase(databaseName)
                .orElseThrow(() -> new SchemaNotFoundException(databaseName)));

        Database newDatabase = Database.builder(database)
                .setOwnerName(principal.getName())
                .setOwnerType(principal.getType())
                .build();

        delegate.alterDatabase(identity, databaseName, toMetastoreApiDatabase(newDatabase));
    }

    @Override
    public void createTable(HiveIdentity identity, Table table, PrincipalPrivileges principalPrivileges)
    {
        delegate.createTable(identity, toMetastoreApiTable(table, principalPrivileges));
    }

    @Override
    public void dropTable(HiveIdentity identity, String databaseName, String tableName, boolean deleteData)
    {
        delegate.dropTable(identity, databaseName, tableName, deleteData);
    }

    @Override
    public void replaceTable(HiveIdentity identity, String databaseName, String tableName, Table newTable, PrincipalPrivileges principalPrivileges)
    {
        alterTable(identity, databaseName, tableName, toMetastoreApiTable(newTable, principalPrivileges));
    }

    @Override
    public void renameTable(HiveIdentity identity, String databaseName, String tableName, String newDatabaseName, String newTableName)
    {
        Optional<org.apache.hadoop.hive.metastore.api.Table> source = delegate.getTable(identity, databaseName, tableName);
        if (source.isEmpty()) {
            throw new TableNotFoundException(new SchemaTableName(databaseName, tableName));
        }
        org.apache.hadoop.hive.metastore.api.Table table = source.get();
        table.setDbName(newDatabaseName);
        table.setTableName(newTableName);
        alterTable(identity, databaseName, tableName, table);
    }

    @Override
    public void commentTable(HiveIdentity identity, String databaseName, String tableName, Optional<String> comment)
    {
        Optional<org.apache.hadoop.hive.metastore.api.Table> source = delegate.getTable(identity, databaseName, tableName);
        if (source.isEmpty()) {
            throw new TableNotFoundException(new SchemaTableName(databaseName, tableName));
        }
        org.apache.hadoop.hive.metastore.api.Table table = source.get();

        Map<String, String> parameters = table.getParameters().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(TABLE_COMMENT))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        comment.ifPresent(value -> parameters.put(TABLE_COMMENT, comment.get()));

        table.setParameters(parameters);
        alterTable(identity, databaseName, tableName, table);
    }

    @Override
    public void addColumn(HiveIdentity identity, String databaseName, String tableName, String columnName, HiveType columnType, String columnComment)
    {
        Optional<org.apache.hadoop.hive.metastore.api.Table> source = delegate.getTable(identity, databaseName, tableName);
        if (source.isEmpty()) {
            throw new TableNotFoundException(new SchemaTableName(databaseName, tableName));
        }
        org.apache.hadoop.hive.metastore.api.Table table = source.get();
        table.getSd().getCols().add(
                new FieldSchema(columnName, columnType.getHiveTypeName().toString(), columnComment));
        alterTable(identity, databaseName, tableName, table);
    }

    @Override
    public void renameColumn(HiveIdentity identity, String databaseName, String tableName, String oldColumnName, String newColumnName)
    {
        Optional<org.apache.hadoop.hive.metastore.api.Table> source = delegate.getTable(identity, databaseName, tableName);
        if (source.isEmpty()) {
            throw new TableNotFoundException(new SchemaTableName(databaseName, tableName));
        }
        org.apache.hadoop.hive.metastore.api.Table table = source.get();
        for (FieldSchema fieldSchema : table.getPartitionKeys()) {
            if (fieldSchema.getName().equals(oldColumnName)) {
                throw new PrestoException(NOT_SUPPORTED, "Renaming partition columns is not supported");
            }
        }
        for (FieldSchema fieldSchema : table.getSd().getCols()) {
            if (fieldSchema.getName().equals(oldColumnName)) {
                fieldSchema.setName(newColumnName);
            }
        }
        alterTable(identity, databaseName, tableName, table);
    }

    @Override
    public void dropColumn(HiveIdentity identity, String databaseName, String tableName, String columnName)
    {
        verifyCanDropColumn(this, identity, databaseName, tableName, columnName);
        org.apache.hadoop.hive.metastore.api.Table table = delegate.getTable(identity, databaseName, tableName)
                .orElseThrow(() -> new TableNotFoundException(new SchemaTableName(databaseName, tableName)));
        table.getSd().getCols().removeIf(fieldSchema -> fieldSchema.getName().equals(columnName));
        alterTable(identity, databaseName, tableName, table);
    }

    private void alterTable(HiveIdentity identity, String databaseName, String tableName, org.apache.hadoop.hive.metastore.api.Table table)
    {
        delegate.alterTable(identity, databaseName, tableName, table);
    }

    @Override
    public Optional<Partition> getPartition(HiveIdentity identity, Table table, List<String> partitionValues)
    {
        return delegate.getPartition(identity, table.getDatabaseName(), table.getTableName(), partitionValues).map(partition -> fromMetastoreApiPartition(table, partition));
    }

    @Override
    public Optional<List<String>> getPartitionNames(HiveIdentity identity, String databaseName, String tableName)
    {
        return delegate.getPartitionNames(identity, databaseName, tableName);
    }

    @Override
    public Optional<List<String>> getPartitionNamesByParts(HiveIdentity identity, String databaseName, String tableName, List<String> parts)
    {
        return delegate.getPartitionNamesByParts(identity, databaseName, tableName, parts);
    }

    @Override
    public Map<String, Optional<Partition>> getPartitionsByNames(HiveIdentity identity, Table table, List<String> partitionNames)
    {
        requireNonNull(partitionNames, "partitionNames is null");
        if (partitionNames.isEmpty()) {
            return ImmutableMap.of();
        }

        Map<String, List<String>> partitionNameToPartitionValuesMap = partitionNames.stream()
                .collect(Collectors.toMap(identity(), HiveUtil::toPartitionValues));
        Map<List<String>, Partition> partitionValuesToPartitionMap = delegate.getPartitionsByNames(identity, table.getDatabaseName(), table.getTableName(), partitionNames).stream()
                .map(partition -> fromMetastoreApiPartition(table, partition))
                .collect(Collectors.toMap(Partition::getValues, identity()));
        ImmutableMap.Builder<String, Optional<Partition>> resultBuilder = ImmutableMap.builder();
        for (Map.Entry<String, List<String>> entry : partitionNameToPartitionValuesMap.entrySet()) {
            Partition partition = partitionValuesToPartitionMap.get(entry.getValue());
            resultBuilder.put(entry.getKey(), Optional.ofNullable(partition));
        }
        return resultBuilder.build();
    }

    private Partition fromMetastoreApiPartition(Table table, org.apache.hadoop.hive.metastore.api.Partition partition)
    {
        if (isAvroTableWithSchemaSet(table)) {
            List<FieldSchema> schema = table.getDataColumns().stream()
                    .map(ThriftMetastoreUtil::toMetastoreApiFieldSchema)
                    .collect(toImmutableList());
            return ThriftMetastoreUtil.fromMetastoreApiPartition(partition, schema);
        }

        return ThriftMetastoreUtil.fromMetastoreApiPartition(partition);
    }

    @Override
    public void addPartitions(HiveIdentity identity, String databaseName, String tableName, List<PartitionWithStatistics> partitions)
    {
        delegate.addPartitions(identity, databaseName, tableName, partitions);
    }

    @Override
    public void dropPartition(HiveIdentity identity, String databaseName, String tableName, List<String> parts, boolean deleteData)
    {
        delegate.dropPartition(identity, databaseName, tableName, parts, deleteData);
    }

    @Override
    public void alterPartition(HiveIdentity identity, String databaseName, String tableName, PartitionWithStatistics partition)
    {
        delegate.alterPartition(identity, databaseName, tableName, partition);
    }

    @Override
    public void createRole(String role, String grantor)
    {
        delegate.createRole(role, grantor);
    }

    @Override
    public void dropRole(String role)
    {
        delegate.dropRole(role);
    }

    @Override
    public Set<String> listRoles()
    {
        return delegate.listRoles();
    }

    @Override
    public void grantRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOption, HivePrincipal grantor)
    {
        delegate.grantRoles(roles, grantees, adminOption, grantor);
    }

    @Override
    public void revokeRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOption, HivePrincipal grantor)
    {
        delegate.revokeRoles(roles, grantees, adminOption, grantor);
    }

    @Override
    public Set<RoleGrant> listRoleGrants(HivePrincipal principal)
    {
        return delegate.listRoleGrants(principal);
    }

    @Override
    public void grantTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee, Set<HivePrivilegeInfo> privileges)
    {
        delegate.grantTablePrivileges(databaseName, tableName, tableOwner, grantee, privileges);
    }

    @Override
    public void revokeTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee, Set<HivePrivilegeInfo> privileges)
    {
        delegate.revokeTablePrivileges(databaseName, tableName, tableOwner, grantee, privileges);
    }

    @Override
    public Set<HivePrivilegeInfo> listTablePrivileges(String databaseName, String tableName, String tableOwner, Optional<HivePrincipal> principal)
    {
        return delegate.listTablePrivileges(databaseName, tableName, tableOwner, principal);
    }

    @Override
    public boolean isImpersonationEnabled()
    {
        return delegate.isImpersonationEnabled();
    }

    @Override
    public Optional<String> getConfigValue(String name)
    {
        return delegate.getConfigValue(name);
    }

    @Override
    public long openTransaction(HiveIdentity identity)
    {
        return delegate.openTransaction(identity);
    }

    @Override
    public void commitTransaction(HiveIdentity identity, long transactionId)
    {
        delegate.commitTransaction(identity, transactionId);
    }

    @Override
    public void sendTransactionHeartbeat(HiveIdentity identity, long transactionId)
    {
        delegate.sendTransactionHeartbeat(identity, transactionId);
    }

    @Override
    public void acquireSharedReadLock(HiveIdentity identity, String queryId, long transactionId, List<SchemaTableName> fullTables, List<HivePartition> partitions)
    {
        delegate.acquireSharedReadLock(identity, queryId, transactionId, fullTables, partitions);
    }

    @Override
    public String getValidWriteIds(HiveIdentity identity, List<SchemaTableName> tables, long currentTransactionId)
    {
        return delegate.getValidWriteIds(identity, tables, currentTransactionId);
    }
}
