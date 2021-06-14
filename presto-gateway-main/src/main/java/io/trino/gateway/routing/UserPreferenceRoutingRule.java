package io.trino.gateway.routing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.Session;
import io.trino.gateway.clustermonitor.SteerDClusterStats;
import io.trino.server.GatewayRequestSessionContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class UserPreferenceRoutingRule
        extends RoutingRuleSpec
{
    private static final RoutingRuleType type = RoutingRuleType.USERPREFERENCE;

    @JsonCreator
    public UserPreferenceRoutingRule(
            @JsonProperty("name") String name,
            @JsonProperty("properties") Map<String, String> properties)
    {
        super(name, type.toString(), properties);
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
