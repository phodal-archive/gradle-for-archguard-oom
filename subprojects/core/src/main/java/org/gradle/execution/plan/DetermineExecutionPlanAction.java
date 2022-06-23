/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.execution.plan;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import org.gradle.api.CircularReferenceException;
import org.gradle.api.GradleException;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.TaskDestroyablesInternal;
import org.gradle.api.internal.tasks.TaskLocalStateInternal;
import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.internal.tasks.properties.PropertyValue;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.internal.graph.CachingDirectedGraphWalker;
import org.gradle.internal.graph.DirectedGraphRenderer;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.reflect.validation.TypeValidationContext;

import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Lists.newLinkedList;
import static java.lang.String.format;
import static org.gradle.execution.plan.NodeSets.sortedListOf;

/**
 * Determines the execution plan, checking for cycles and making sure `finalizedBy` constraints are honored.
 *
 * The final plan is communicated via the mutation of the given `nodeMapping`, `ordinalNodeAccess` and `finalizers` collections.
 * `entryNodes` is not changed by this class.
 *
 * <h2>Note about finalizers</h2>
 * A dependency of a finalizer must not run until it is known to be needed by something else that should run.
 * So if the dependency is only required by a finalizer, then it should not start until the finalizer is ready to start
 * (ie the finalized task has completed). But if the dependency is also required by some node reachable from the
 * command-line tasks, then it should start as soon as its dependencies are ready.
 * Or if the dependency is required by multiple finalizers, then it should not start until one of those finalizer
 * is ready to start.
 */
class DetermineExecutionPlanAction {
    private final DefaultExecutionPlan.NodeMapping nodeMapping;
    private final OrdinalNodeAccess ordinalNodeAccess;
    private final Set<Node> entryNodes;
    private final Set<Node> finalizers;

    private final LinkedList<NodeInVisitingSegment> nodeQueue = newLinkedList();
    private final HashMultimap<Node, Integer> visitingNodes = HashMultimap.create();
    private final Deque<GraphEdge> walkedShouldRunAfterEdges = new ArrayDeque<>();
    private final Deque<Node> path = new ArrayDeque<>();
    private final Map<Node, Integer> planBeforeVisiting = new HashMap<>();

    private int visitingSegmentCounter = 0;

    /**
     * See {@link DetermineExecutionPlanAction}
     */
    public DetermineExecutionPlanAction(DefaultExecutionPlan.NodeMapping nodeMapping, OrdinalNodeAccess ordinalNodeAccess, Set<Node> entryNodes, Set<Node> finalizers) {
        this.entryNodes = entryNodes;
        this.nodeMapping = nodeMapping;
        this.ordinalNodeAccess = ordinalNodeAccess;
        this.finalizers = finalizers;
    }

    public void run() {
        updateFinalizerGroups();
        processEntryNodes();
        processNodeQueue();
        createOrdinalRelationships();

        ordinalNodeAccess.createInterNodeRelationships();
        ordinalNodeAccess.getAllNodes().forEach(nodeMapping::add);
    }

    private void updateFinalizerGroups() {
        if (finalizers.isEmpty()) {
            return;
        }
        // Collect the finalizers and their dependencies so that each node is ordered before all of its dependencies
        LinkedList<Node> nodes = new LinkedList<>();
        Set<Node> visiting = new HashSet<>();
        Set<Node> visited = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>(finalizers);
        while (!queue.isEmpty()) {
            Node node = queue.peek();
            if (visited.contains(node)) {
                // Already visited node, skip
                queue.remove();
            } else if (visiting.add(node)) {
                // Haven't seen this node
                for (Node successor : node.getDependencySuccessors()) {
                    queue.addFirst(successor);
                }
            } else {
                // Have visited the dependencies of this node, add it to the start of the list (so that it is earlier in the list that
                // all of its dependencies)
                visiting.remove(node);
                visited.add(node);
                nodes.addFirst(node);
            }
        }
        for (Node node : nodes) {
            node.updateGroupOfFinalizer();
        }
        finalizers.clear();
    }

    private void processEntryNodes() {
        for (Node node : entryNodes) {
            nodeQueue.add(new NodeInVisitingSegment(node, visitingSegmentCounter++));
        }
    }

    private void processNodeQueue() {
        while (!nodeQueue.isEmpty()) {
            final NodeInVisitingSegment nodeInVisitingSegment = nodeQueue.peekFirst();
            final int currentSegment = nodeInVisitingSegment.visitingSegment;
            final Node node = nodeInVisitingSegment.node;

            if (node.isDoNotIncludeInPlan() || nodeMapping.contains(node)) {
                // Discard the node because it has already been visited or should not be included, for example:
                // - it has already executed in another execution plan
                // - it is reachable only via a must-run-after or should-run-after edge
                // - it is filtered
                nodeQueue.removeFirst();
                visitingNodes.remove(node, currentSegment);
                maybeRemoveProcessedShouldRunAfterEdge(node);
                continue;
            }

            if (visitingNodes.put(node, currentSegment)) {
                // Have not seen this node before - add its dependencies to the head of the queue and leave this
                // node in the queue
                if (node instanceof TaskNode) {
                    TaskNode taskNode = (TaskNode) node;
                    recordEdgeIfArrivedViaShouldRunAfter(path, taskNode);
                    removeShouldRunAfterSuccessorsIfTheyImposeACycle(taskNode, nodeInVisitingSegment.visitingSegment);
                    takePlanSnapshotIfCanBeRestoredToCurrentTask(planBeforeVisiting, taskNode);
                }

                // Add any finalizers to the queue just after the current node
                for (Node finalizer : node.getFinalizers()) {
                    addFinalizerToQueue(visitingSegmentCounter++, finalizer);
                }

                for (Node successor : node.getAllSuccessorsInReverseOrder()) {
                    if (visitingNodes.containsEntry(successor, currentSegment)) {
                        if (!walkedShouldRunAfterEdges.isEmpty()) {
                            //remove the last walked should run after edge and restore state from before walking it
                            GraphEdge toBeRemoved = walkedShouldRunAfterEdges.pop();
                            // Should run after edges only exist between tasks, so this cast is safe
                            TaskNode sourceTask = (TaskNode) toBeRemoved.from;
                            TaskNode targetTask = (TaskNode) toBeRemoved.to;
                            sourceTask.removeShouldSuccessor(targetTask);
                            restorePath(path, toBeRemoved);
                            restoreQueue(toBeRemoved);
                            restoreExecutionPlan(planBeforeVisiting, toBeRemoved);
                            break;
                        } else {
                            onOrderingCycle(successor, node);
                        }
                    }
                    nodeQueue.addFirst(new NodeInVisitingSegment(successor, currentSegment));
                }
                path.push(node);
            } else {
                // Have visited this node's dependencies - add it to the end of the plan
                nodeQueue.removeFirst();
                maybeRemoveProcessedShouldRunAfterEdge(node);
                visitingNodes.remove(node, currentSegment);
                path.pop();
                nodeMapping.add(node);

                for (Node dependency : node.getDependencySuccessors()) {
                    dependency.getMutationInfo().consumingNodes.add(node);
                }
            }
        }
    }

    private void createOrdinalRelationships() {
        for (Node node : nodeMapping) {
            node.maybeUpdateOrdinalGroup();
            createOrdinalRelationships(node);
        }
    }

    private void addFinalizerToQueue(int visitingSegmentCounter, Node finalizer) {
        int insertPosition = 1;
        int pos = 0;
        for (NodeInVisitingSegment segment : nodeQueue) {
            if (segment.node == finalizer) {
                // Already later in the queue
                return;
            }
            // Need to insert the finalizer immediately after the last node that it finalizes
            if (finalizer.getFinalizingSuccessors().contains(segment.node) && pos > insertPosition) {
                insertPosition = pos;
            }
            pos++;
        }
        nodeQueue.add(insertPosition, new NodeInVisitingSegment(finalizer, visitingSegmentCounter));
    }

    private void maybeRemoveProcessedShouldRunAfterEdge(Node node) {
        GraphEdge edge = walkedShouldRunAfterEdges.peek();
        if (edge != null && edge.to.equals(node)) {
            walkedShouldRunAfterEdges.pop();
        }
    }

    private void restoreExecutionPlan(Map<Node, Integer> planBeforeVisiting, GraphEdge toBeRemoved) {
        int count = planBeforeVisiting.get(toBeRemoved.from);
        nodeMapping.retainFirst(count);
    }

    private void restoreQueue(GraphEdge toBeRemoved) {
        NodeInVisitingSegment nextInQueue = null;
        while (nextInQueue == null || !toBeRemoved.from.equals(nextInQueue.node)) {
            nextInQueue = nodeQueue.peekFirst();
            visitingNodes.remove(nextInQueue.node, nextInQueue.visitingSegment);
            if (!toBeRemoved.from.equals(nextInQueue.node)) {
                nodeQueue.removeFirst();
            }
        }
    }

    private void restorePath(Deque<Node> path, GraphEdge toBeRemoved) {
        Node removedFromPath = null;
        while (!toBeRemoved.from.equals(removedFromPath)) {
            removedFromPath = path.pop();
        }
    }

    private void removeShouldRunAfterSuccessorsIfTheyImposeACycle(TaskNode node, int visitingSegment) {
        Iterables.removeIf(
            node.getShouldSuccessors(),
            input -> visitingNodes.containsEntry(input, visitingSegment)
        );
    }

    private void takePlanSnapshotIfCanBeRestoredToCurrentTask(Map<Node, Integer> planBeforeVisiting, TaskNode node) {
        if (!node.getShouldSuccessors().isEmpty()) {
            planBeforeVisiting.put(node, nodeMapping.size());
        }
    }

    private void recordEdgeIfArrivedViaShouldRunAfter(Deque<Node> path, TaskNode node) {
        Node previous = path.peek();
        if (!(previous instanceof TaskNode)) {
            return;
        }
        TaskNode previousTaskNode = (TaskNode) previous;
        if (previousTaskNode.getShouldSuccessors().contains(node)) {
            walkedShouldRunAfterEdges.push(new GraphEdge(previous, node));
        }
    }

    private void onOrderingCycle(Node successor, Node currentNode) {
        List<Set<Node>> cycles = findCycles(successor);
        if (cycles.isEmpty()) {
            // TODO: This isn't correct. This means that we've detected a cycle while determining the execution plan, but the graph walker did not find one.
            // https://github.com/gradle/gradle/issues/2293
            throw new GradleException("Misdetected cycle between " + currentNode + " and " + successor + ". Help us by reporting this to https://github.com/gradle/gradle/issues/2293");
        }
        StringWriter cycleString = renderOrderingCycle(cycles.get(0));
        throw new CircularReferenceException(format("Circular dependency between the following tasks:%n%s", cycleString));
    }

    private List<Set<Node>> findCycles(Node successor) {
        CachingDirectedGraphWalker<Node, Void> graphWalker = new CachingDirectedGraphWalker<>((node, values, connectedNodes) -> {
            connectedNodes.addAll(node.getDependencySuccessors());
            if (node instanceof TaskNode) {
                TaskNode taskNode = (TaskNode) node;
                connectedNodes.addAll(taskNode.getMustSuccessors());
                connectedNodes.addAll(taskNode.getFinalizingSuccessors());
            }
        });
        graphWalker.add(successor);
        return graphWalker.findCycles();
    }

    private StringWriter renderOrderingCycle(Set<Node> nodes) {
        List<Node> cycle = sortedListOf(nodes);

        DirectedGraphRenderer<Node> graphRenderer = new DirectedGraphRenderer<>(
            (it, output) -> output.withStyle(StyledTextOutput.Style.Identifier).text(it),
            (it, values, connectedNodes) -> {
                for (Node dependency : cycle) {
                    if (it.hasHardSuccessor(dependency)) {
                        connectedNodes.add(dependency);
                    }
                }
            });
        StringWriter writer = new StringWriter();
        graphRenderer.renderTo(cycle.get(0), writer);
        return writer;
    }

    private void createOrdinalRelationships(Node node) {
        if (!(node instanceof TaskNode)) {
            return;
        }

        OrdinalGroup ordinal = node.getOrdinal();
        if (ordinal == null) {
            return;
        }

        TaskNode taskNode = (TaskNode) node;
        TaskClassifier taskClassifier = classifyTask(taskNode);

        if (taskClassifier.isDestroyer()) {
            // Create (or get) a destroyer ordinal node that depends on the dependencies of this task node
            OrdinalNode ordinalNode = ordinalNodeAccess.getOrCreateDestroyableLocationNode(ordinal);
            ordinalNode.addDependenciesFrom(taskNode);

            Node precedingProducersNode = ordinalNodeAccess.getPrecedingProducerLocationNode(ordinal);
            if (precedingProducersNode != null) {
                // Depend on any previous producer ordinal nodes (i.e. any producer ordinal nodes with a lower ordinal)
                taskNode.addDependencySuccessor(precedingProducersNode);
            }
        } else if (taskClassifier.isProducer()) {
            // Create (or get) a producer ordinal node that depends on the dependencies of this task node
            OrdinalNode ordinalNode = ordinalNodeAccess.getOrCreateOutputLocationNode(ordinal);
            ordinalNode.addDependenciesFrom(taskNode);

            Node precedingDestroyersNode = ordinalNodeAccess.getPrecedingDestroyerLocationNode(ordinal);
            if (precedingDestroyersNode != null) {
                // Depend on any previous destroyer ordinal nodes (i.e. any destroyer ordinal nodes with a lower ordinal)
                taskNode.addDependencySuccessor(precedingDestroyersNode);
            }
        }
    }

    /**
     * Walk the properties of the task to determine if it is a destroyer or a producer (or neither).
     */
    private TaskClassifier classifyTask(TaskNode taskNode) {
        TaskClassifier taskClassifier = new TaskClassifier();
        TaskInternal task = taskNode.getTask();

        PropertyWalker propertyWalker = propertyWalkerOf(task);
        propertyWalker.visitProperties(task, TypeValidationContext.NOOP, taskClassifier);
        task.getOutputs().visitRegisteredProperties(taskClassifier);
        if (taskClassifier.isDestroyer()) {
            // avoid walking further properties after discovering the task is destroyer
            return taskClassifier;
        }

        ((TaskDestroyablesInternal) task.getDestroyables()).visitRegisteredProperties(taskClassifier);
        if (taskClassifier.isDestroyer()) {
            // avoid walking further properties after discovering the task is destroyer
            return taskClassifier;
        }

        ((TaskLocalStateInternal) task.getLocalState()).visitRegisteredProperties(taskClassifier);

        return taskClassifier;
    }

    private PropertyWalker propertyWalkerOf(TaskInternal task) {
        return ((ProjectInternal) task.getProject()).getServices().get(PropertyWalker.class);
    }

    private static class TaskClassifier extends PropertyVisitor.Adapter {
        private boolean isProducer;
        private boolean isDestroyer;

        @Override
        public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
            isProducer = true;
        }

        @Override
        public void visitDestroyableProperty(Object value) {
            isDestroyer = true;
        }

        @Override
        public void visitLocalStateProperty(Object value) {
            isProducer = true;
        }

        public boolean isProducer() {
            return isProducer;
        }

        public boolean isDestroyer() {
            return isDestroyer;
        }
    }

    private static class NodeInVisitingSegment {
        private final Node node;
        private final int visitingSegment;

        private NodeInVisitingSegment(Node node, int visitingSegment) {
            this.node = node;
            this.visitingSegment = visitingSegment;
        }

        @Override
        public String toString() {
            return "NodeInVisitingSegment{" +
                "node=" + node +
                ", visitingSegment=" + visitingSegment +
                '}';
        }
    }

    private static class GraphEdge {
        private final Node from;
        private final Node to;

        private GraphEdge(Node from, Node to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return "GraphEdge{" +
                "from=" + from +
                ", to=" + to +
                '}';
        }
    }
}
