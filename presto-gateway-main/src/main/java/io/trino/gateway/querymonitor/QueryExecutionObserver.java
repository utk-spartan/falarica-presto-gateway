package io.trino.gateway.querymonitor;

import io.trino.execution.QueryStats;

public interface QueryExecutionObserver
{
    public void observe(QueryStats queryStats);
}
