package io.trino.server.remotetask;

import io.airlift.http.server.HttpServerConfig;
import io.airlift.http.server.HttpServerInfo;
import io.trino.execution.LocationFactory;
import io.trino.execution.TaskId;
import io.trino.metadata.InternalNode;
import io.trino.metadata.InternalNodeManager;
import io.trino.spi.QueryId;

import javax.inject.Inject;

import java.net.URI;

import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static java.util.Objects.requireNonNull;

public class SteerDHttpLocationFactory
        implements LocationFactory
{
    private final InternalNodeManager nodeManager;
    private final URI baseUri;

    // Can't use the original HttpLocationFactory because it was referring to InternalCommunicationModule,
    // which creates problems with Gateway Server. We are reading config from http server config instead of
    // InternalCommunicationConfig (which somehow looks incorrect from Presto perspective too)
    @Inject
    public SteerDHttpLocationFactory(InternalNodeManager nodeManager, HttpServerInfo httpServerInfo, HttpServerConfig config)
    {
        this(nodeManager, config.isHttpsEnabled() ? httpServerInfo.getHttpsUri() : httpServerInfo.getHttpUri());
    }

    public SteerDHttpLocationFactory(InternalNodeManager nodeManager, URI baseUri)
    {
        this.nodeManager = requireNonNull(nodeManager, "nodeManager is null");
        this.baseUri = requireNonNull(baseUri, "baseUri is null");
    }

    @Override
    public URI createQueryLocation(QueryId queryId)
    {
        requireNonNull(queryId, "queryId is null");
        return uriBuilderFrom(baseUri)
                .appendPath("/v1/query")
                .appendPath(queryId.toString())
                .build();
    }

    @Override
    public URI createLocalTaskLocation(TaskId taskId)
    {
        return createTaskLocation(nodeManager.getCurrentNode(), taskId);
    }

    @Override
    public URI createTaskLocation(InternalNode node, TaskId taskId)
    {
        requireNonNull(node, "node is null");
        requireNonNull(taskId, "taskId is null");
        return uriBuilderFrom(node.getInternalUri())
                .appendPath("/v1/task")
                .appendPath(taskId.toString())
                .build();
    }

    @Override
    public URI createMemoryInfoLocation(InternalNode node)
    {
        requireNonNull(node, "node is null");
        return uriBuilderFrom(node.getInternalUri())
                .appendPath("/v1/memory").build();
    }
}
