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
package io.prestosql.sql.planner.assertions;

import io.prestosql.Session;
import io.prestosql.cost.StatsProvider;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.UnnestNode;
import io.prestosql.sql.planner.plan.UnnestNode.Mapping;
import io.prestosql.sql.tree.Expression;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.sql.planner.assertions.MatchResult.NO_MATCH;
import static java.util.Objects.requireNonNull;

final class UnnestMatcher
        implements Matcher
{
    private final List<String> replicateSymbols;
    private final List<String> unnestSymbols;
    private final Optional<String> ordinalitySymbol;
    private final JoinNode.Type type;
    private final Optional<Expression> filter;

    public UnnestMatcher(List<String> replicateSymbols, List<String> unnestSymbols, Optional<String> ordinalitySymbol, JoinNode.Type type, Optional<Expression> filter)
    {
        this.replicateSymbols = requireNonNull(replicateSymbols, "replicateSymbols is null");
        this.unnestSymbols = requireNonNull(unnestSymbols, "mappings is null");
        this.ordinalitySymbol = requireNonNull(ordinalitySymbol, "ordinalitySymbol is null");
        this.type = requireNonNull(type, "type is null");
        this.filter = requireNonNull(filter, "filter is null");
    }

    @Override
    public boolean shapeMatches(PlanNode node)
    {
        if (!(node instanceof UnnestNode)) {
            return false;
        }

        UnnestNode unnestNode = (UnnestNode) node;
        return unnestNode.getJoinType() == type;
    }

    @Override
    public MatchResult detailMatches(PlanNode node, StatsProvider stats, Session session, Metadata metadata, SymbolAliases symbolAliases)
    {
        checkState(shapeMatches(node), "Plan testing framework error: shapeMatches returned false in detailMatches in %s", this.getClass().getName());
        UnnestNode unnestNode = (UnnestNode) node;

        if (unnestNode.getReplicateSymbols().size() != replicateSymbols.size()) {
            return NO_MATCH;
        }
        if (!replicateSymbols.stream()
                .map(symbolAliases::get)
                .map(Symbol::from)
                .collect(toImmutableList())
                .equals(unnestNode.getReplicateSymbols())) {
            return NO_MATCH;
        }

        if (unnestNode.getMappings().size() != unnestSymbols.size()) {
            return NO_MATCH;
        }
        if (!unnestSymbols.stream()
                .map(symbolAliases::get)
                .map(Symbol::from)
                .collect(toImmutableList())
                .equals(unnestNode.getMappings().stream()
                        .map(Mapping::getInput)
                        .collect(toImmutableList()))) {
            return NO_MATCH;
        }

        if (ordinalitySymbol.isPresent() != unnestNode.getOrdinalitySymbol().isPresent()) {
            return NO_MATCH;
        }

        if (!type.equals(unnestNode.getJoinType())) {
            return NO_MATCH;
        }

        if (filter.isPresent() != unnestNode.getFilter().isPresent()) {
            return NO_MATCH;
        }
        if (filter.isEmpty()) {
            return MatchResult.match();
        }
        if (!new ExpressionVerifier(symbolAliases).process(unnestNode.getFilter().get(), filter.get())) {
            return NO_MATCH;
        }

        return MatchResult.match();
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .omitNullValues()
                .add("type", type)
                .add("replicateSymbols", replicateSymbols)
                .add("unnestSymbols", unnestSymbols)
                .add("ordinalitySymbol", ordinalitySymbol.orElse(null))
                .add("filter", filter.orElse(null))
                .toString();
    }
}
