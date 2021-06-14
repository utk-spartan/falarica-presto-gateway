package io.trino.gateway.routing;

public enum RoutingRuleType
{
    COLOCATION,
    RANDOMCLUSTER,
    ROUNDROBIN,
    CPUUTILIZATION,
    RUNNINGQUERY,
    QUEUEDQUERY,
    USERPREFERENCE,
    CATALOGPREFERENCE,
    CLOUDBURST
}
