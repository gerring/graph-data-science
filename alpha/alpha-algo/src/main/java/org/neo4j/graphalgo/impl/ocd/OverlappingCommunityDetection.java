/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.impl.ocd;

import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.logging.Log;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class OverlappingCommunityDetection extends Algorithm<OverlappingCommunityDetection, CommunityAffiliations> {
    private static final double TOLERANCE = 0.00001;

    private Graph graph;
    private AffiliationInitializer initializer;
    private ExecutorService executorService;
    private final Log log;
    private final int gradientConcurrency;

    public OverlappingCommunityDetection(
        Graph graph,
        AffiliationInitializer initializer,
        ExecutorService executorService,
        Log log,
        int gradientConcurrency
    ) {
        this.graph = graph;
        this.initializer = initializer;
        this.executorService = executorService;
        this.log = log;
        this.gradientConcurrency = gradientConcurrency;
        if (graph.nodeCount() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Overlapping community detection only supports graphs with 2^32-1 nodes.");
        }
    }

    @Override
    public CommunityAffiliations compute() {
        CommunityAffiliations communityAffiliations = initializer.initialize(graph);
        double oldGain = communityAffiliations.gain();
        System.out.println(String.format("Iteration 0: Gain is %s.", oldGain));
        double newGain;
        int iteration = 0;
        while ((newGain = update(communityAffiliations)) > oldGain + TOLERANCE) {
            oldGain = newGain;
            iteration++;
            System.out.println(String.format("Iteration %d: Gain is %s.", iteration, oldGain));
        }
        return communityAffiliations;
    }


    double update(CommunityAffiliations communityAffiliations) {
        AtomicInteger queue = new AtomicInteger(0);
        // create tasks
        final Collection<? extends Runnable> tasks = ParallelUtil.tasks(
            gradientConcurrency,
            () -> new GradientStepTask(queue, communityAffiliations)
        );
        // run
        ParallelUtil.run(tasks, executorService);
        return communityAffiliations.gain();
    }

    @Override
    public OverlappingCommunityDetection me() {
        return this;
    }

    @Override
    public void release() {
        graph = null;
        executorService = null;
        initializer = null;

    }

    class GradientStepTask implements Runnable {
        private final AtomicInteger queue;
        private final CommunityAffiliations communityAffiliations;
        private final BacktrackingLineSearch lineSearch;

        GradientStepTask(AtomicInteger queue, CommunityAffiliations communityAffiliations) {
            this.queue = queue;
            this.communityAffiliations = communityAffiliations;
            this.lineSearch = new BacktrackingLineSearch();
        }

        @Override
        public void run() {
            int nodeU;
            while ((nodeU = queue.getAndIncrement()) < graph.nodeCount() && running()) {
                GainFunction blockLoss = communityAffiliations.blockGain(nodeU);
                SparseVector gradient = blockLoss.gradient();
                double learningRate = lineSearch.search(
                    blockLoss,
                    communityAffiliations.nodeAffiliations(nodeU),
                    gradient
                );
                communityAffiliations.updateNodeAffiliations(nodeU, gradient.multiply(learningRate));
            }
        }
    }
}
