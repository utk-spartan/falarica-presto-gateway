package io.trino.gateway.routing;

import java.util.List;

public interface RoutingPPolicy
{
    public List<RoutingRule> getRules();
}
