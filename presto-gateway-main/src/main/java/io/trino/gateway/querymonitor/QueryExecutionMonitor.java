package io.trino.gateway.querymonitor;

public interface QueryExecutionMonitor
{
    void monitorQueryExecution(QueryExecutionContext context);

    void start();

    void stop();

    void scheduleQueryHistoryCleanup();
}
