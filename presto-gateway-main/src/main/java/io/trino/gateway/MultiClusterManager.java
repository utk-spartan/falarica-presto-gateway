package io.trino.gateway;

import io.airlift.json.JsonCodec;
import io.trino.execution.QueryInfo;
import io.trino.gateway.persistence.ClusterDetail;
import io.trino.gateway.persistence.QueryDetails2;
import io.trino.gateway.persistence.dao.Cluster;
import io.trino.gateway.persistence.dao.RoutingPolicy;
import io.trino.gateway.routing.QueuedQueryRoutingRule;
import io.trino.gateway.routing.RoutingPolicySelectorSpec;
import io.trino.gateway.routing.RoutingPolicySpec;
import io.trino.gateway.routing.RoutingRuleSpec;
import io.trino.gateway.routing.RunningQueryRoutingRule;

import java.util.List;

public interface MultiClusterManager
{
    List<ClusterDetail> getAllClusters();

    List<ClusterDetail> getAllActiveClusters();

    public List<ClusterDetail> getAllActiveClusterStats();

    public List<ClusterDetail> getAllClusters(String location);

    Cluster addCluster(ClusterDetail info);

    boolean deleteCluster(String name);

    void addRoutingRule(RoutingRuleSpec ruleSpec);

    void addRoutingPolicy(RoutingPolicySpec policySpec);

    void addRoutingPolicySelector(RoutingPolicySelectorSpec selectorSpec);

    void updatePolicy(RoutingPolicySpec policySpec);

    void updateQueuedQueryBasedRule(QueuedQueryRoutingRule queuedQueryRoutingRule);

    void updateRunningQueryBasedRule(RunningQueryRoutingRule runningQueryBasedRule);

    List<RoutingPolicySpec> getAllRoutingPolicies();

    List<RoutingRuleSpec> getAllRoutingRules();

    List<RoutingPolicySelectorSpec> getAllRoutingPolicySelectors();

    boolean deleteRoutingPolicy(String name);

    boolean deleteRoutingRule(String name);

    boolean deleteRoutingPolicySelector(String name);

    List<RoutingPolicy> loadAllRulesFromDB();

    ClusterDetail updateCluster(ClusterDetail into);

    List<QueryDetails2> getAllQueries();

    void setQueryInfoCodec(JsonCodec<QueryInfo> codec);

    List<RoutingRuleSpec> loadAllRoutingRules();

    List<RoutingPolicySpec> loadAllRoutingPolicy();
}
