/*
 * Copyright (c) 2017 "Neo4j, Inc." <http://neo4j.com>
 *
 * This file is part of Neo4j Graph Algorithms <http://github.com/neo4j-contrib/neo4j-graph-algorithms>.
 *
 * Neo4j Graph Algorithms is free software: you can redistribute it and/or modify
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
package org.neo4j.graphalgo.bench;

import org.neo4j.graphalgo.TestDatabaseCreator;
import org.neo4j.graphalgo.centrality.ClosenessCentralityProc;
import org.neo4j.graphalgo.compat.MapUtil;
import org.neo4j.graphalgo.core.huge.HugeGraph;
import org.neo4j.graphalgo.core.utils.Pools;
import org.neo4j.graphalgo.core.utils.ProgressTimer;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Threads(1)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ClosenessCentralityBenchmark extends BaseBenchmark {

    private static final RelationshipType RELATIONSHIP_TYPE = RelationshipType.withName("TYPE");

    @Param({"30"})
    public int netSize;

    private GraphDatabaseAPI db;

    private Map<String, Object> params;

    @Setup
    public void setup() throws Exception {
        db = TestDatabaseCreator.createTestDatabase();
        registerProcedures(ClosenessCentralityProc.class);

        try (ProgressTimer ignored = ProgressTimer.start(l -> System.out.println("setup took " + l + "ms"))) {
            createNet(netSize); // size^2 nodes; size^3 edges
        }

        params = MapUtil.map("graph", HugeGraph.TYPE);
    }

    @TearDown
    public void tearDown() {
        db.shutdown();
        Pools.DEFAULT.shutdown();
    }

    private void createNet(int size) {
        try (Transaction tx = db.beginTx()) {
            List<Node> temp = null;
            for (int i = 0; i < size; i++) {
                List<Node> line = createLine(size);
                if (null != temp) {
                    for (int j = 0; j < size; j++) {
                        for (int k = 0; k < size; k++) {
                            if (j == k) {
                                continue;
                            }
                            temp.get(j).createRelationshipTo(line.get(k), RELATIONSHIP_TYPE);
                        }
                    }
                }
                temp = line;
            }
            tx.success();
        }
    }

    private List<Node> createLine(int length) {
        ArrayList<Node> nodes = new ArrayList<>();
        Node temp = db.createNode();
        nodes.add(temp);
        for (int i = 1; i < length; i++) {
            Node node = db.createNode();
            nodes.add(temp);
            temp.createRelationshipTo(node, RELATIONSHIP_TYPE);
            temp = node;
        }
        return nodes;
    }

    @Benchmark
    public Object _01_benchmark() {
        return runQuery(
            "CALL algo.closeness('','', {write:false, stats:false, graph: $graph}) YIELD " +
            "nodes, loadMillis, computeMillis, writeMillis",
            params,
            r -> r.stream().count()
        );
    }

}
