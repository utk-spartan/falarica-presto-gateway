package io.trino.dispatcher;

import io.trino.Session;
import io.trino.execution.QueryPreparer;
import io.trino.execution.QueryStateMachine;
import io.trino.execution.warnings.WarningCollector;
import io.trino.execution.warnings.WarningCollectorFactory;
import io.trino.gateway.QueryHistoryManager;
import io.trino.metadata.Metadata;
import io.trino.security.AccessControl;
import io.trino.server.protocol.Slug;
import io.trino.spi.resourcegroups.ResourceGroupId;
import io.trino.sql.parser.SqlParser;
import io.trino.transaction.TransactionId;
import io.trino.transaction.TransactionManager;

import javax.inject.Inject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.Executor;

import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.trino.util.StatementUtils.isTransactionControlStatement;
import static java.util.Objects.requireNonNull;

public class SteerDDispatchQueryFactory
        implements DispatchQueryFactory
{
    private final Executor executor;

    private final TransactionManager transactionManager;
    private final AccessControl accessControl;
    private final Metadata metadata;
    private final WarningCollectorFactory warningCollectorFactory;
    final SqlParser sqlParser;
    final QueryHistoryManager queryHistoryManager;

    @Inject
    public SteerDDispatchQueryFactory(TransactionManager transactionManager,
            AccessControl accessControl,
            Metadata metadata,
            DispatchExecutor dispatchExecutor,
            WarningCollectorFactory warningCollectorFactory,
            SqlParser sqlParser,
            QueryHistoryManager queryHistoryManager)
    {
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.executor = requireNonNull(dispatchExecutor, "executorService is null").getExecutor();
        this.warningCollectorFactory = requireNonNull(warningCollectorFactory, "warningCollectorFactory is null");
        this.sqlParser = sqlParser;
        //this.routingManager = routingManager;
        this.queryHistoryManager = queryHistoryManager;
    }

    @Override
    public DispatchQuery createDispatchQuery(Session session, String query,
            QueryPreparer.PreparedQuery preparedQuery, Slug slug, ResourceGroupId resourceGroup)
    {
        WarningCollector warningCollector = warningCollectorFactory.create();
        URI uri = null;
        try {
            // use dummy uri.
            uri = uriBuilderFrom(new URI("http://localhost:8088"))
                    .appendPath("/v1/query")
                    .appendPath(session.getQueryId().toString())
                    .build();
        }
        catch (URISyntaxException e) {
        }

        QueryStateMachine stateMachine = QueryStateMachine.begin(
                query,
                preparedQuery.getPrepareSql(),
                session,
                uri,
                resourceGroup,
                isTransactionControlStatement(preparedQuery.getStatement()),
                transactionManager,
                accessControl,
                executor,
                metadata,
                warningCollector,
                Optional.empty());
//java.lang.String,java.util.Optional<java.lang.String>,io.trino.Session,java.net.URI,io.trino.spi.resourcegroups.ResourceGroupId,boolean,io.trino.transaction.TransactionManager,io.trino.security.AccessControl,java.util.concurrent.Executor,io.trino.metadata.Metadata,io.trino.execution.warnings.WarningCollector,java.util.Optional<io.trino.spi.resourcegroups.QueryType>
//java.lang.String,java.util.Optional<java.lang.String>,io.trino.Session,java.net.URI,io.trino.spi.resourcegroups.ResourceGroupId,boolean,io.trino.transaction.TransactionManager,io.trino.security.AccessControl,com.google.common.util.concurrent.ListeningExecutorService,io.trino.metadata.Metadata,io.trino.execution.warnings.WarningCollector

        if (session.getTransactionId().isEmpty()) {
            // TODO: make autocommit isolation level a session parameter
            TransactionId transactionId = transactionManager.beginTransaction(true);
            session = session.beginTransactionId(transactionId, transactionManager, accessControl);
        }
        return new SteerDDispatchedQuery(
                session,
                query,
                preparedQuery,
                slug,
                resourceGroup,
                stateMachine,
                queryHistoryManager);
    }
}
