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

package org.apache.doris.nereids.rules.analysis;

import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.WindowExpression;
import org.apache.doris.nereids.trees.expressions.visitor.DefaultExpressionRewriter;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.algebra.Aggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalHaving;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalQualify;
import org.apache.doris.nereids.util.ExpressionUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * fill up missing slot for qualify
 */
public class FillUpQualifyMissingSlot extends FillUpMissingSlots {
    @Override
    public List<Rule> buildRules() {
        return ImmutableList.of(
            /*
               qualify -> project(distinct)
               qualify -> project
               qualify -> project(distinct) -> agg
               qualify -> project(distinct) -> having -> agg
             */
            RuleType.FILL_UP_QUALIFY_PROJECT.build(
                logicalQualify(logicalProject())
                    .then(qualify -> {
                        LogicalProject<Plan> project = qualify.child();
                        return createPlan(project, qualify.getConjuncts(), (newConjuncts, projects) -> {
                            LogicalProject<Plan> bottomProject = new LogicalProject<>(projects, project.child());
                            LogicalQualify<Plan> logicalQualify = new LogicalQualify<>(newConjuncts, bottomProject);
                            ImmutableList<NamedExpression> copyOutput = ImmutableList.copyOf(project.getOutput());
                            return new LogicalProject<>(copyOutput, project.isDistinct(), logicalQualify);
                        });
                    })
            ),
            /*
               qualify -> agg
             */
            RuleType.FILL_UP_QUALIFY_AGGREGATE.build(
                logicalQualify(aggregate()).then(qualify -> {
                    Aggregate<Plan> agg = qualify.child();
                    Resolver resolver = new Resolver(agg);
                    qualify.getConjuncts().forEach(resolver::resolve);
                    return createPlan(resolver, agg, (r, a) -> {
                        Set<Expression> newConjuncts = ExpressionUtils.replace(
                                qualify.getConjuncts(), r.getSubstitution());
                        boolean notChanged = newConjuncts.equals(qualify.getConjuncts());
                        if (notChanged && a.equals(agg)) {
                            return null;
                        }
                        return notChanged ? qualify.withChildren(a) : new LogicalQualify<>(newConjuncts, a);
                    });
                })
            ),
            /*
               qualify -> having -> agg
             */
            RuleType.FILL_UP_QUALIFY_HAVING_AGGREGATE.build(
                logicalQualify(logicalHaving(aggregate())).then(qualify -> {
                    LogicalHaving<Aggregate<Plan>> having = qualify.child();
                    Aggregate<Plan> agg = qualify.child().child();
                    Resolver resolver = new Resolver(agg);
                    qualify.getConjuncts().forEach(resolver::resolve);
                    return createPlan(resolver, agg, (r, a) -> {
                        Set<Expression> newConjuncts = ExpressionUtils.replace(
                                qualify.getConjuncts(), r.getSubstitution());
                        boolean notChanged = newConjuncts.equals(qualify.getConjuncts());
                        if (notChanged && a.equals(agg)) {
                            return null;
                        }
                        return notChanged ? qualify.withChildren(having.withChildren(a)) :
                            new LogicalQualify<>(newConjuncts, having.withChildren(a));
                    });
                })
            ),
            /*
               qualify -> having -> project
               qualify -> having -> project(distinct)
             */
            RuleType.FILL_UP_QUALIFY_HAVING_PROJECT.build(
                logicalQualify(logicalHaving(logicalProject())).then(qualify -> {
                    LogicalHaving<LogicalProject<Plan>> having = qualify.child();
                    LogicalProject<Plan> project = qualify.child().child();
                    return createPlan(project, qualify.getConjuncts(), (newConjuncts, projects) -> {
                        ImmutableList<NamedExpression> copyOutput = ImmutableList.copyOf(project.getOutput());
                        if (project.isDistinct()) {
                            Set<Slot> notExistedInProject = having.getExpressions().stream()
                                    .map(Expression::getInputSlots)
                                    .flatMap(Set::stream)
                                    .filter(s -> !projects.contains(s))
                                    .collect(Collectors.toSet());
                            List<NamedExpression> output = new ArrayList<>();
                            output.addAll(projects);
                            output.addAll(notExistedInProject);
                            LogicalQualify<LogicalProject<Plan>> logicalQualify =
                                    new LogicalQualify<>(newConjuncts, new LogicalProject<>(output, project.child()));
                            return having.withChildren(project.withProjects(copyOutput).withChildren(logicalQualify));
                        } else {
                            return new LogicalProject<>(copyOutput, new LogicalQualify<>(newConjuncts,
                                    having.withChildren(project.withProjects(projects))));
                        }
                    });
                })
            )
        );
    }

    private Plan createPlan(LogicalProject<Plan> project, Set<Expression> conjuncts, PlanGenerator planGenerator) {
        Set<Slot> projectOutputSet = project.getOutputSet();
        List<NamedExpression> newOutputSlots = Lists.newArrayList();
        Set<Expression> newConjuncts = new HashSet<>();
        for (Expression conjunct : conjuncts) {
            conjunct = conjunct.accept(new DefaultExpressionRewriter<List<NamedExpression>>() {
                @Override
                public Expression visitWindow(WindowExpression window, List<NamedExpression> context) {
                    Alias alias = new Alias(window);
                    context.add(alias);
                    return alias.toSlot();
                }
            }, newOutputSlots);
            newConjuncts.add(conjunct);
        }
        Set<Slot> notExistedInProject = conjuncts.stream()
                .map(Expression::getInputSlots)
                .flatMap(Set::stream)
                .filter(s -> !projectOutputSet.contains(s))
                .collect(Collectors.toSet());

        newOutputSlots.addAll(notExistedInProject);
        if (newOutputSlots.isEmpty()) {
            return null;
        }
        List<NamedExpression> projects = ImmutableList.<NamedExpression>builder()
                .addAll(project.getProjects())
                .addAll(newOutputSlots).build();

        return planGenerator.apply(newConjuncts, projects);
    }

    interface PlanGenerator {
        Plan apply(Set<Expression> newConjuncts, List<NamedExpression> projects);
    }
}
