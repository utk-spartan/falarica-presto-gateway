package io.trino.gateway.routing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.Session;
import io.trino.gateway.clustermonitor.SteerDClusterStats;
import io.trino.server.GatewayRequestSessionContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CatalogPreferenceRoutingRule
        extends RoutingRuleSpec
{
    private static final RoutingRuleType type = RoutingRuleType.CATALOGPREFERENCE;

    @JsonCreator
    public CatalogPreferenceRoutingRule(
            @JsonProperty("name") String name,
            @JsonProperty("staticRule") Map<String, String> staticRule)
    {
        super(name, type.toString(), staticRule);
    }

    @Override
    public List<SteerDClusterStats> apply(GatewayRequestSessionContext queryContext,
            List<SteerDClusterStats> clusterStats,
            Optional<Session> session)
    {
        return null;
    }

    @Override
    public void validateProperties()
    {
    }
}
