// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.rewrite.logical;

import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SubqueryExpr;
import org.apache.doris.nereids.trees.expressions.functions.agg.AggregateFunction;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.visitor.PlanVisitor;
import org.apache.doris.nereids.util.ExpressionUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * extract effective predicates.
 */
public class EffectivePredicatesExtractor extends PlanVisitor<Set<Expression>, Void> {

    PredicatePropagation propagation = new PredicatePropagation();

    @Override
    public Set<Expression> visit(Plan plan, Void context) {
        return Sets.newHashSet();
    }

    @Override
    public Set<Expression> visitLogicalFilter(LogicalFilter<? extends Plan> filter, Void context) {
        List<Expression> predicates = ExpressionUtils.extractConjunction(filter.getPredicates()).stream()
                .filter(p -> {
                    if (p instanceof SubqueryExpr) {
                        SubqueryExpr subqueryExpr = (SubqueryExpr) p;
                        return subqueryExpr.getCorrelateSlots().isEmpty();
                    }
                    return true;
                }).collect(Collectors.toList());
        predicates.addAll(filter.child().accept(this, context));
        return getAvailableExpressions(Sets.newHashSet(predicates), filter);
    }

    @Override
    public Set<Expression> visitLogicalJoin(LogicalJoin<? extends Plan, ? extends Plan> join, Void context) {
        Set<Expression> predicates = Sets.newHashSet();
        Set<Expression> leftPredicates = join.left().accept(this, context);
        Set<Expression> rightPredicates = join.right().accept(this, context);
        switch (join.getJoinType()) {
            case INNER_JOIN:
            case CROSS_JOIN:
                predicates.addAll(leftPredicates);
                predicates.addAll(rightPredicates);
                join.getOnClauseCondition().map(on -> predicates.addAll(ExpressionUtils.extractConjunction(on)));
                break;
            case LEFT_SEMI_JOIN:
                predicates.addAll(leftPredicates);
                join.getOnClauseCondition().map(on -> predicates.addAll(ExpressionUtils.extractConjunction(on)));
                break;
            case RIGHT_SEMI_JOIN:
                predicates.addAll(rightPredicates);
                join.getOnClauseCondition().map(on -> predicates.addAll(ExpressionUtils.extractConjunction(on)));
                break;
            case LEFT_OUTER_JOIN:
            case LEFT_ANTI_JOIN:
                predicates.addAll(leftPredicates);
                break;
            case RIGHT_OUTER_JOIN:
            case RIGHT_ANTI_JOIN:
                predicates.addAll(rightPredicates);
                break;
            default:
        }
        return getAvailableExpressions(predicates, join);
    }

    @Override
    public Set<Expression> visitLogicalProject(LogicalProject<? extends Plan> project, Void context) {
        Set<Expression> childPredicates = project.child().accept(this, context);
        Map<Expression, Slot> expressionSlotMap = project.getAliasToProducer()
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Entry::getValue, Entry::getKey));
        Expression expression = ExpressionUtils.replace(ExpressionUtils.and(Lists.newArrayList(childPredicates)),
                expressionSlotMap);
        Set<Expression> predicates = Sets.newHashSet();
        predicates.addAll(ExpressionUtils.extractConjunction(expression));
        return getAvailableExpressions(predicates, project);
    }

    @Override
    public Set<Expression> visitLogicalAggregate(LogicalAggregate<? extends Plan> aggregate, Void context) {
        Set<Expression> childPredicates = aggregate.child().accept(this, context);
        Map<Expression, Slot> expressionSlotMap = aggregate.getOutputExpressions()
                .stream()
                .filter(this::hasAgg)
                .collect(Collectors.toMap(
                        namedExpr -> {
                            if (namedExpr instanceof Alias) {
                                return ((Alias) namedExpr).child();
                            } else {
                                return namedExpr;
                            }
                        }, NamedExpression::toSlot)
                );
        Expression expression = ExpressionUtils.replace(ExpressionUtils.and(Lists.newArrayList(childPredicates)),
                expressionSlotMap);
        Set<Expression> predicates = Sets.newHashSet();
        predicates.addAll(ExpressionUtils.extractConjunction(expression));
        return getAvailableExpressions(predicates, aggregate);
    }

    public Set<Expression> getAvailableExpressions(Set<Expression> predicates, Plan plan) {
        predicates.addAll(propagation.infer(Lists.newArrayList(predicates)));
        return predicates.stream()
                .filter(p -> new HashSet<>(plan.getOutput()).containsAll(p.getInputSlots()))
                .collect(Collectors.toSet());
    }

    private boolean hasAgg(Expression expression) {
        if (expression instanceof AggregateFunction) {
            return true;
        }
        for (Expression child : expression.children()) {
            if (hasAgg(child)) {
                return true;
            }
        }
        return false;
    }
}
