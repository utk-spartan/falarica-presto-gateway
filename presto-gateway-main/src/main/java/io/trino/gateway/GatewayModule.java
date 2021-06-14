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
package io.trino.gateway;

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.airlift.http.server.HttpServerConfig;
import io.airlift.slice.Slice;
import io.trino.GroupByHashPageIndexerFactory;
import io.trino.PagesIndexPageSorter;
import io.trino.SystemSessionProperties;
import io.trino.client.NodeVersion;
import io.trino.connector.ConnectorManager;
import io.trino.cost.StatsAndCosts;
import io.trino.dispatcher.DispatchExecutor;
import io.trino.dispatcher.DispatchQueryFactory;
import io.trino.dispatcher.FailedDispatchQueryFactory;
import io.trino.dispatcher.SteerDDispatchQueryFactory;
import io.trino.event.QueryMonitor;
import io.trino.event.QueryMonitorConfig;
import io.trino.execution.ExecutionFailureInfo;
import io.trino.execution.LocationFactory;
import io.trino.execution.NodeTaskMap;
import io.trino.execution.QueryIdGenerator;
import io.trino.execution.QueryInfo;
import io.trino.execution.QueryManagerConfig;
import io.trino.execution.QueryPreparer;
import io.trino.execution.StageInfo;
import io.trino.execution.TaskInfo;
import io.trino.execution.TaskManagerConfig;
import io.trino.execution.TaskStatus;
import io.trino.execution.resourcegroups.InternalResourceGroupManager;
import io.trino.execution.resourcegroups.LegacyResourceGroupConfigurationManager;
import io.trino.execution.resourcegroups.ResourceGroupManager;
import io.trino.execution.scheduler.NodeScheduler;
import io.trino.execution.scheduler.NodeSchedulerConfig;
import io.trino.execution.scheduler.TopologyAwareNodeSelectorModule;
import io.trino.failuredetector.FailureDetector;
import io.trino.failuredetector.NoOpFailureDetector;
import io.trino.gateway.clustermonitor.ClusterMonitor;
import io.trino.gateway.clustermonitor.ClusterStatsObserver;
import io.trino.gateway.clustermonitor.DefaultPrestoClusterStatsObserver;
import io.trino.gateway.clustermonitor.PullBasedPrestoClusterMonitor;
import io.trino.gateway.persistence.DataStoreConfig;
import io.trino.gateway.persistence.JDBCConnectionManager;
import io.trino.gateway.querymonitor.PullBasedPrestoQueryExecutionMonitor;
import io.trino.gateway.querymonitor.QueryExecutionMonitor;
import io.trino.gateway.routing.RoutingManager;
import io.trino.gateway.ui.GatewayWebUiModule;
import io.trino.index.IndexManager;
import io.trino.memory.MemoryManagerConfig;
import io.trino.memory.NodeMemoryConfig;
import io.trino.memory.SteerDPrestoClusterMemoryManager;
import io.trino.metadata.AnalyzePropertyManager;
import io.trino.metadata.CatalogManager;
import io.trino.metadata.ColumnPropertyManager;
import io.trino.metadata.HandleJsonModule;
import io.trino.metadata.InMemoryNodeManager;
import io.trino.metadata.InternalNodeManager;
import io.trino.metadata.Metadata;
import io.trino.metadata.MetadataManager;
import io.trino.metadata.SchemaPropertyManager;
import io.trino.metadata.SessionPropertyManager;
import io.trino.metadata.StaticCatalogStore;
import io.trino.metadata.StaticCatalogStoreConfig;
import io.trino.metadata.TablePropertyManager;
import io.trino.operator.GatewayOperatorStats;
import io.trino.operator.OperatorStats;
import io.trino.operator.PagesIndex;
import io.trino.plugin.resourcegroups.db.DbResourceGroupConfig;
import io.trino.server.ExpressionSerialization;
import io.trino.server.GatewayPluginManager;
import io.trino.server.PluginManagerConfig;
import io.trino.server.QuerySessionSupplier;
import io.trino.server.SessionPropertyDefaults;
import io.trino.server.SessionSupplier;
import io.trino.server.SliceSerialization;
import io.trino.server.remotetask.SteerDHttpLocationFactory;
import io.trino.spi.PageIndexerFactory;
import io.trino.spi.PageSorter;
import io.trino.spi.memory.ClusterMemoryPoolManager;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeSignature;
import io.trino.split.PageSinkManager;
import io.trino.split.PageSinkProvider;
import io.trino.split.PageSourceManager;
import io.trino.split.PageSourceProvider;
import io.trino.split.SplitManager;
import io.trino.sql.SqlEnvironmentConfig;
import io.trino.sql.analyzer.FeaturesConfig;
import io.trino.sql.gen.JoinCompiler;
import io.trino.sql.gen.OrderingCompiler;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.parser.SqlParserOptions;
import io.trino.sql.planner.NodePartitioningManager;
import io.trino.sql.planner.TypeAnalyzer;
import io.trino.sql.tree.Expression;
import io.trino.transaction.ForTransactionManager;
import io.trino.transaction.SteerDTransactionManager;
import io.trino.transaction.TransactionManager;
import io.trino.transaction.TransactionManagerConfig;
import io.trino.type.TypeDeserializer;
import io.trino.type.TypeSignatureDeserializer;
import io.trino.util.FinalizerService;
import io.trino.version.EmbedVersion;

import javax.inject.Singleton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.airlift.jaxrs.JaxrsBinder.jaxrsBinder;
import static io.airlift.json.JsonBinder.jsonBinder;
import static io.airlift.json.JsonCodecBinder.jsonCodecBinder;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class GatewayModule
        extends AbstractConfigurationAwareModule
{
    @Override
    public void setup(Binder binder)
    {
        configBinder(binder).bindConfigDefaults(HttpServerConfig.class, httpServerConfig -> {
            httpServerConfig.setAdminEnabled(false);
        });

        jsonCodecBinder(binder).bindJsonCodec(QueryInfo.class);
        jsonCodecBinder(binder).bindJsonCodec(TaskStatus.class);
        jsonCodecBinder(binder).bindJsonCodec(StageInfo.class);
        jsonCodecBinder(binder).bindJsonCodec(TaskInfo.class);
        jsonCodecBinder(binder).bindJsonCodec(OperatorStats.class);
        jsonCodecBinder(binder).bindJsonCodec(GatewayOperatorStats.class);
        jsonCodecBinder(binder).bindJsonCodec(ExecutionFailureInfo.class);
        jsonCodecBinder(binder).bindJsonCodec(StatsAndCosts.class);

        // handle resolver
        binder.install(new HandleJsonModule());

        httpClientBinder(binder).bindHttpClient("gateway", ForGateway.class);
        // httpClientBinder(binder).bindHttpClient("watcher", ForProxy.class);

        configBinder(binder).bindConfig(GatewayConfig.class);
        configBinder(binder).bindConfig(K8sClusterConfig.class, "k8scluster");
        configBinder(binder).bindConfig(DataStoreConfig.class, "datastore");
        configBinder(binder).bindConfig(JwtHandlerConfig.class, "gateway");
        configBinder(binder).bindConfig(DbResourceGroupConfig.class);

        jaxrsBinder(binder).bind(JDBCConnectionManager.class);
        binder.bind(MultiClusterManager.class).to(PrestoClusterManager.class).in(Scopes.SINGLETON);
        jaxrsBinder(binder).bind(RoutingManager.class);
        jaxrsBinder(binder).bind(QueryHistoryManager.class);

        jaxrsBinder(binder).bind(GatewayResource.class);
        jaxrsBinder(binder).bind(ClusterResource.class);
        jaxrsBinder(binder).bind(ClusterEditorResource.class);
        jaxrsBinder(binder).bind(DefaultPrestoClusterStatsObserver.class);
        jaxrsBinder(binder).bind(SteerDThrowableMapper.class);

        binder.bind(ClusterStatsObserver.class).to(DefaultPrestoClusterStatsObserver.class).in(Scopes.SINGLETON);

        binder.bind(ClusterMonitor.class).to(PullBasedPrestoClusterMonitor.class).in(Scopes.SINGLETON);

        binder.bind(QueryExecutionMonitor.class).to(PullBasedPrestoQueryExecutionMonitor.class).in(Scopes.SINGLETON);

        binder.bind(SteerDPrestoClusterMemoryManager.class).in(Scopes.SINGLETON);
        binder.bind(ClusterMemoryPoolManager.class).to(SteerDPrestoClusterMemoryManager.class).in(Scopes.SINGLETON);

        binder.bind(InternalResourceGroupManager.class).in(Scopes.SINGLETON);
        newExporter(binder).export(InternalResourceGroupManager.class).withGeneratedName();
        binder.bind(ResourceGroupManager.class).to(InternalResourceGroupManager.class);
        binder.bind(LegacyResourceGroupConfigurationManager.class).in(Scopes.SINGLETON);

        configBinder(binder).bindConfig(PluginManagerConfig.class);
        binder.bind(GatewayPluginManager.class).in(Scopes.SINGLETON);

        configBinder(binder).bindConfig(QueryManagerConfig.class);
        configBinder(binder).bindConfig(TaskManagerConfig.class);
        configBinder(binder).bindConfig(MemoryManagerConfig.class);
        configBinder(binder).bindConfig(FeaturesConfig.class);
        configBinder(binder).bindConfig(NodeMemoryConfig.class);
        configBinder(binder).bindConfig(SqlEnvironmentConfig.class);

        binder.bind(SqlParser.class).in(Scopes.SINGLETON);
        SqlParserOptions sqlParserOptions = new SqlParserOptions();
        //sqlParserOptions.useEnhancedErrorHandler(serverConfig.isEnhancedErrorReporting());
        binder.bind(SqlParserOptions.class).toInstance(sqlParserOptions);

        //binder.bind(StaticCatalogStore.class).in(Scopes.SINGLETON);
        //configBinder(binder).bindConfig(StaticCatalogStoreConfig.class);
        // schema properties
        binder.bind(SchemaPropertyManager.class).in(Scopes.SINGLETON);

        // table properties
        binder.bind(TablePropertyManager.class).in(Scopes.SINGLETON);

        // column properties
        binder.bind(ColumnPropertyManager.class).in(Scopes.SINGLETON);
        // analyze properties
        binder.bind(AnalyzePropertyManager.class).in(Scopes.SINGLETON);

        // Type
        binder.bind(TypeAnalyzer.class).in(Scopes.SINGLETON);
        jsonBinder(binder).addDeserializerBinding(Type.class).to(TypeDeserializer.class);
        jsonBinder(binder).addDeserializerBinding(TypeSignature.class).to(TypeSignatureDeserializer.class);
        newSetBinder(binder, Type.class);

        binder.bind(MetadataManager.class).in(Scopes.SINGLETON);
        binder.bind(Metadata.class).to(MetadataManager.class).in(Scopes.SINGLETON);

        // slice
        jsonBinder(binder).addSerializerBinding(Slice.class).to(SliceSerialization.SliceSerializer.class);
        jsonBinder(binder).addDeserializerBinding(Slice.class).to(SliceSerialization.SliceDeserializer.class);

        // expression
        jsonBinder(binder).addSerializerBinding(Expression.class).to(ExpressionSerialization.ExpressionSerializer.class);
        jsonBinder(binder).addDeserializerBinding(Expression.class).to(ExpressionSerialization.ExpressionDeserializer.class);

        binder.bind(SessionSupplier.class).to(QuerySessionSupplier.class).in(Scopes.SINGLETON);
        binder.bind(QueryPreparer.class).in(Scopes.SINGLETON);
        binder.bind(QueryIdGenerator.class).in(Scopes.SINGLETON);
        binder.bind(SessionPropertyManager.class).in(Scopes.SINGLETON);
        binder.bind(SystemSessionProperties.class).in(Scopes.SINGLETON);
        binder.bind(SessionPropertyDefaults.class).in(Scopes.SINGLETON);
        // execution

        binder.bind(QueryIdGenerator.class).in(Scopes.SINGLETON);
        // binder.bind(QueryManager.class).to(SqlQueryManager.class).in(Scopes.SINGLETON);
        // newExporter(binder).export(QueryManager.class).withGeneratedName();
        binder.bind(QueryPreparer.class).in(Scopes.SINGLETON);
        binder.bind(SessionSupplier.class).to(QuerySessionSupplier.class).in(Scopes.SINGLETON);
        binder.bind(NodeVersion.class).toInstance(new NodeVersion("12"));
        binder.bind(EmbedVersion.class).in(Scopes.SINGLETON);

        jsonCodecBinder(binder).bindJsonCodec(StatsAndCosts.class);
        configBinder(binder).bindConfig(QueryMonitorConfig.class);
        binder.bind(QueryMonitor.class).in(Scopes.SINGLETON);
        binder.bind(LocationFactory.class).to(SteerDHttpLocationFactory.class).in(Scopes.SINGLETON);

        binder.bind(DispatchQueryFactory.class).to(SteerDDispatchQueryFactory.class);

        // Dispatcher
        //binder.bind(DispatchManager.class).in(Scopes.SINGLETON);
        binder.bind(FailedDispatchQueryFactory.class).in(Scopes.SINGLETON);
        binder.bind(DispatchExecutor.class).in(Scopes.SINGLETON);

        binder.bind(SteerDDispatchManager.class).in(Scopes.SINGLETON);
        // binder.bind(InternalAuthenticationManager.class);
        binder.bind(JsonWebTokenHandler.class).in(Scopes.SINGLETON);
        // binder.bind(ExecutorCleanup.class).in(Scopes.SINGLETON);

        // ******* START - FOR CATALOGS AND DATA CONNECTORS ******* //
        // ***************************************************************** //
        binder.bind(IndexManager.class).in(Scopes.SINGLETON);
        binder.bind(CatalogManager.class).in(Scopes.SINGLETON);
        binder.bind(InternalNodeManager.class).to(InMemoryNodeManager.class).in(Scopes.SINGLETON);
        install(new TopologyAwareNodeSelectorModule());
        configBinder(binder).bindConfig(NodeSchedulerConfig.class);
        binder.bind(FinalizerService.class).in(Scopes.SINGLETON);
        // split manager
        binder.bind(SplitManager.class).in(Scopes.SINGLETON);

        // node partitioning manager
        binder.bind(NodePartitioningManager.class).in(Scopes.SINGLETON);

        // index manager
        binder.bind(IndexManager.class).in(Scopes.SINGLETON);

        binder.bind(FailureDetector.class)
                .to(NoOpFailureDetector.class)
                .in(Scopes.SINGLETON);
        // data stream provider
        binder.bind(PageSourceManager.class).in(Scopes.SINGLETON);
        binder.bind(PageSourceProvider.class).to(PageSourceManager.class).in(Scopes.SINGLETON);

        // page sink provider
        binder.bind(PageSinkManager.class).in(Scopes.SINGLETON);
        binder.bind(PageSinkProvider.class).to(PageSinkManager.class).in(Scopes.SINGLETON);
        binder.bind(NodeScheduler.class).in(Scopes.SINGLETON);
        binder.bind(NodeTaskMap.class).in(Scopes.SINGLETON);
        newExporter(binder).export(NodeScheduler.class).withGeneratedName();

        // PageSorter
        binder.bind(PageSorter.class).to(PagesIndexPageSorter.class).in(Scopes.SINGLETON);

        // PageIndexer
        binder.bind(PageIndexerFactory.class).to(GroupByHashPageIndexerFactory.class).in(Scopes.SINGLETON);
        binder.bind(JoinCompiler.class).in(Scopes.SINGLETON);
        newExporter(binder).export(JoinCompiler.class).withGeneratedName();
        binder.bind(PagesIndex.Factory.class).to(PagesIndex.DefaultFactory.class);
        binder.bind(OrderingCompiler.class).in(Scopes.SINGLETON);
        newExporter(binder).export(OrderingCompiler.class).withGeneratedName();
        binder.bind(StaticCatalogStore.class).in(Scopes.SINGLETON);
        configBinder(binder).bindConfig(StaticCatalogStoreConfig.class);
        binder.bind(ConnectorManager.class).in(Scopes.SINGLETON);
        // ******* END - FOR CATALOGS AND DATA CONNECTORS ******* //
        // ***************************************************************** //

        install(new GatewayWebUiModule());
        configBinder(binder).bindConfig(TransactionManagerConfig.class);
    }

    @Provides
    @Singleton
    @ForTransactionManager
    public static ScheduledExecutorService createTransactionIdleCheckExecutor()
    {
        return newSingleThreadScheduledExecutor(daemonThreadsNamed("transaction-idle-check"));
    }

    @Provides
    @Singleton
    @ForTransactionManager
    public static ExecutorService createTransactionFinishingExecutor()
    {
        return newCachedThreadPool(daemonThreadsNamed("transaction-finishing-%s"));
    }

    @Provides
    @Singleton
    public static TransactionManager createTransactionManager(
            TransactionManagerConfig config,
            CatalogManager catalogManager,
            MultiClusterManager clusterManager,
            EmbedVersion embedVersion,
            @ForTransactionManager ScheduledExecutorService idleCheckExecutor,
            @ForTransactionManager ExecutorService finishingExecutor)
    {
        return SteerDTransactionManager.create(config, idleCheckExecutor, catalogManager, clusterManager, embedVersion.embedVersion(finishingExecutor));
    }
}
