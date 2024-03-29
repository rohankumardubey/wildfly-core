/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.controller.operations.coordination;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CALLER_TYPE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.IGNORED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNNING_SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.USER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.domain.controller.ServerIdentity;
import org.jboss.as.host.controller.IgnoredNonAffectedServerGroupsUtil;
import org.jboss.as.host.controller.IgnoredNonAffectedServerGroupsUtil.ServerConfigInfo;
import org.jboss.as.host.controller.ignored.IgnoredDomainResourceRegistry;
import org.jboss.dmr.ModelNode;

/**
* Support class for the execution of an operation on an individual host controller.
*
* @author Brian Stansberry (c) 2011 Red Hat Inc.
*/
interface HostControllerExecutionSupport {

    /**
     * Gets the operation (if any) that should be run on the host controller itself
     * @return the operation to run on the host controller, or {@code null}
     */
    ModelNode getDomainOperation();

    /**
     * Gets the operations that should be run on the servers managed by the host controller.
     *
     * @param provider provider of server operations this object can delegate to if needed
     *
     * @return map of servers to the operation they should execute. Will not be {@code null} but may be empty
     */
    Map<ServerIdentity, ModelNode> getServerOps(ServerOperationProvider provider);

    /**
     * Gets the result of this operation (if any) on this host controller, along with any operations
     * needed to effect the operation on the servers managed by this host controller, in the
     * format expected by the host controller that is coordinating overall execution across the domain.
     *
     * @param resultNode node to which the result should be attached
     *
     * @return the formatted result
     */
    ModelNode getFormattedDomainResult(ModelNode resultNode);

    /**
     * Returns whether the operation puts the host in the reload-required state.
     *
     * @return {@code true} if the operation puts the host in the reload-required state
     */
    boolean isReloadRequired();

    /**
     * Callback for then the controller transaction has completed.
     *
     * @param rollback Whether the transaction rolled back or not. {@code true} indicates it was rolled back; {@code false}
     *                 indicates it was committed.
     */
    void complete(boolean rollback);

    /**
     * Provider of server level operations necessary to effect a given domain or host level operation on the servers
     * managed by this host controller.
     */
    interface ServerOperationProvider {

        /**
         * Gets the server level operations necessary to effect a given domain or host level operation on the servers.
         *
         * @param domainOp the domain or host level operation
         * @param address the address of the domain level operation
         *
         * @return map of servers to the operation they should execute. Will not be {@code null} but may be empty
         */
        Map<Set<ServerIdentity>, ModelNode> getServerOperations(ModelNode domainOp, PathAddress address);
    }

    /** Provides a reference to a ModelNode representation of the domain model to {@link Factory} */
    interface DomainModelProvider {
        /**
         * Gets the domain model resource
         * @return the resource. Cannot be {@code null}
         */
        Resource getDomainModel();
    }

    /** Provides a factory method for creating {@link HostControllerExecutionSupport} instances */
    class Factory {

        /**
         * Create a HostControllerExecutionSupport for a given operation.
         *
         *
         * @param context
         * @param operation the operation
         * @param hostName the name of the host executing the operation
         * @param domainModelProvider source for the domain model
         * @param ignoredDomainResourceRegistry registry of resource addresses that should be ignored
         * @throws OperationFailedException
         *
         * @return the HostControllerExecutionSupport
         */
        public static HostControllerExecutionSupport create(OperationContext context, final ModelNode operation,
                                                            final String hostName,
                                                            final DomainModelProvider domainModelProvider,
                                                            final IgnoredDomainResourceRegistry ignoredDomainResourceRegistry,
                                                            final boolean isRemoteDomainControllerIgnoreUnaffectedConfiguration,
                                                            final ExtensionRegistry extensionRegistry) throws OperationFailedException {
            String targetHost = null;
            PathElement runningServerTarget = null;
            ModelNode runningServerOp = null;

            final PathAddress address = PathAddress.pathAddress(operation.get(OP_ADDR));
            if (address.size() > 0) {
                PathElement first = address.getElement(0);
                if (HOST.equals(first.getKey()) && !first.isMultiTarget()) {
                    targetHost = first.getValue();
                    if (address.size() > 1 && RUNNING_SERVER.equals(address.getElement(1).getKey())) {
                        runningServerTarget = address.getElement(1);
                        ModelNode relativeAddress = new ModelNode().setEmptyList();
                        for (int i = 2; i < address.size(); i++) {
                            PathElement element = address.getElement(i);
                            relativeAddress.add(element.getKey(), element.getValue());
                        }
                        runningServerOp = operation.clone();
                        runningServerOp.get(OP_ADDR).set(relativeAddress);
                    }
                }
            }

            HostControllerExecutionSupport result;


            if (targetHost != null && !hostName.equals(targetHost)) {
                // HostControllerExecutionSupport representing another host
                result = new IgnoredOpExecutionSupport(ignoredDomainResourceRegistry);
            }
            else if (runningServerTarget != null) {
                // HostControllerExecutionSupport representing a server op
                final Resource domainModel = domainModelProvider.getDomainModel();
                final Resource hostModel = domainModel.getChild(PathElement.pathElement(HOST, targetHost));
                if (runningServerTarget.isMultiTarget()) {
                    return new DomainOpExecutionSupport(ignoredDomainResourceRegistry, operation, PathAddress.EMPTY_ADDRESS);
                } else {
                    final String serverName = runningServerTarget.getValue();
                    // TODO prevent NPE
                    final String serverGroup = hostModel.getChild(PathElement.pathElement(SERVER_CONFIG, serverName)).getModel().require(GROUP).asString();
                    final ServerIdentity serverIdentity = new ServerIdentity(targetHost, serverGroup, serverName);
                    result = new DirectServerOpExecutionSupport(ignoredDomainResourceRegistry, serverIdentity, runningServerOp);
                }
            }
            else if (COMPOSITE.equals(operation.require(OP).asString())) {
                // Recurse into the steps to see what's required
                if (operation.hasDefined(STEPS)) {
                    List<HostControllerExecutionSupport> parsedSteps = new ArrayList<HostControllerExecutionSupport>();
                    for (ModelNode step : operation.get(STEPS).asList()) {
                        // Propagate the caller-type=user header
                        if (operation.hasDefined(OPERATION_HEADERS, CALLER_TYPE) && operation.get(OPERATION_HEADERS, CALLER_TYPE).asString().equals(USER)) {
                            step = step.clone();
                            step.get(OPERATION_HEADERS, CALLER_TYPE).set(USER);
                        }
                        parsedSteps.add(create(context, step, hostName, domainModelProvider, ignoredDomainResourceRegistry, isRemoteDomainControllerIgnoreUnaffectedConfiguration, extensionRegistry));
                    }
                    result = new MultiStepOpExecutionSupport(ignoredDomainResourceRegistry, parsedSteps);
                }
                else {
                    // Will fail later
                    result = new DomainOpExecutionSupport(ignoredDomainResourceRegistry, operation, address);
                }
            }
            else if (targetHost == null && isResourceExcluded(context, ignoredDomainResourceRegistry, isRemoteDomainControllerIgnoreUnaffectedConfiguration, domainModelProvider, hostName, address, extensionRegistry, operation)) {
                result = new IgnoredOpExecutionSupport(ignoredDomainResourceRegistry);
            }
            else {
                result = new DomainOpExecutionSupport(ignoredDomainResourceRegistry, operation, address);
            }

            return result;

        }

        private static boolean isResourceExcluded(OperationContext context, IgnoredDomainResourceRegistry ignoredDomainResourceRegistry, boolean isRemoteDomainControllerIgnoreUnaffectedConfiguration, DomainModelProvider domainModelProvider, String hostName, PathAddress address, ExtensionRegistry extensionRegistry, ModelNode operation) {
            if (ignoredDomainResourceRegistry.getIgnoredClonedProfileRegistry().checkIgnoredProfileClone(operation)) {
                return true;
            }
            if (ignoredDomainResourceRegistry.isResourceExcluded(address)) {
                return true;
            }
            if (isRemoteDomainControllerIgnoreUnaffectedConfiguration) {
                IgnoredNonAffectedServerGroupsUtil util = IgnoredNonAffectedServerGroupsUtil.create(extensionRegistry);
                Set<ServerConfigInfo> serverConfigs = util.getServerConfigsOnSlave(domainModelProvider.getDomainModel().getChild(PathElement.pathElement(HOST, hostName)));
                return util.ignoreOperation(domainModelProvider.getDomainModel(), serverConfigs, address);
            }
            return false;
        }

        private abstract static class AbstractOpExecutionSupport implements HostControllerExecutionSupport {
            private final IgnoredDomainResourceRegistry.IgnoredClonedProfileRegistry ignoredClonedProfileRegistry;

            private AbstractOpExecutionSupport(final IgnoredDomainResourceRegistry ignoredDomainResourceRegistry) {
                this.ignoredClonedProfileRegistry = ignoredDomainResourceRegistry.getIgnoredClonedProfileRegistry();
            }

            @Override
            public boolean isReloadRequired() {
                return ignoredClonedProfileRegistry.isReloadRequired();
            }

            @Override
            public void complete(boolean rollback) {
                ignoredClonedProfileRegistry.complete(rollback);
            }
        }

        private static class IgnoredOpExecutionSupport extends SimpleOpExecutionSupport {

            private IgnoredOpExecutionSupport(final IgnoredDomainResourceRegistry ignoredDomainResourceRegistry) {
                super(ignoredDomainResourceRegistry);
            }

            @Override
            public ModelNode getDomainOperation() {
                return null;
            }

            @Override
            public Map<ServerIdentity, ModelNode> getServerOps(ServerOperationProvider provider) {
                return Collections.emptyMap();
            }
        }

        private static class DirectServerOpExecutionSupport extends SimpleOpExecutionSupport {
            private Map<ServerIdentity, ModelNode> serverOps;

            private DirectServerOpExecutionSupport(final IgnoredDomainResourceRegistry ignoredDomainResourceRegistry,
                                                   final ServerIdentity serverIdentity, ModelNode serverOp) {
                super(ignoredDomainResourceRegistry);
                this.serverOps = Collections.singletonMap(serverIdentity, serverOp);
            }

            @Override
            public ModelNode getDomainOperation() {
                return null;
            }

            @Override
            public Map<ServerIdentity, ModelNode> getServerOps(ServerOperationProvider provider) {
                return serverOps;
            }
        }

        private static class DomainOpExecutionSupport extends SimpleOpExecutionSupport {

            private final ModelNode domainOp;
            private final PathAddress domainOpAddress;

            private DomainOpExecutionSupport(final IgnoredDomainResourceRegistry ignoredDomainResourceRegistry,
                                             ModelNode domainOp, final PathAddress domainOpAddress) {
                super(ignoredDomainResourceRegistry);
                this.domainOp = domainOp;
                this.domainOpAddress = domainOpAddress;
            }

            @Override
            public ModelNode getDomainOperation() {
                return domainOp;
            }

            @Override
            public Map<ServerIdentity, ModelNode> getServerOps(ServerOperationProvider provider) {
                // TODO change ServerOperationResolver to just provide the unbundled map
                Map<Set<ServerIdentity>, ModelNode> bundled = provider.getServerOperations(domainOp, domainOpAddress);
                Map<ServerIdentity, ModelNode> unbundled = new HashMap<ServerIdentity, ModelNode>();
                for (Map.Entry<Set<ServerIdentity>, ModelNode> entry : bundled.entrySet()) {
                    ModelNode op = entry.getValue();
                    for (ServerIdentity id : entry.getKey()) {
                        unbundled.put(id, op);
                    }
                }
                return unbundled;
            }
        }


        private abstract static class SimpleOpExecutionSupport extends AbstractOpExecutionSupport {
            private SimpleOpExecutionSupport(final IgnoredDomainResourceRegistry ignoredDomainResourceRegistry) {
                super(ignoredDomainResourceRegistry);
            }

            @Override
            public ModelNode getFormattedDomainResult(ModelNode resultNode) {
                return resultNode.clone();
            }
        }

        private static class MultiStepOpExecutionSupport extends AbstractOpExecutionSupport {

            private final List<HostControllerExecutionSupport> steps;

            private MultiStepOpExecutionSupport(final IgnoredDomainResourceRegistry ignoredDomainResourceRegistry,
                                                final List<HostControllerExecutionSupport> steps) {
                super(ignoredDomainResourceRegistry);
                this.steps = steps;
            }

            public Map<ServerIdentity, ModelNode> getServerOps(ServerOperationProvider provider) {
                final Map<ServerIdentity, ModelNode> result = new HashMap<ServerIdentity, ModelNode>();
                int stepNum = 1;
                for (HostControllerExecutionSupport step : steps) {
                    String stepLabel = "step-" + stepNum++;
                    Map<ServerIdentity, ModelNode> stepResults = step.getServerOps(provider);
                    for (Map.Entry<ServerIdentity, ModelNode> entry : stepResults.entrySet()) {
                        ModelNode serverNode = result.get(entry.getKey());
                        if (serverNode == null) {
                            serverNode = new ModelNode();
                            result.put(entry.getKey(), serverNode);
                        }
                        serverNode.get(stepLabel).set(entry.getValue());
                    }
                }
                return result;
            }

            @Override
            public ModelNode getDomainOperation() {

                List<ModelNode> domainSteps = new ArrayList<ModelNode>();
                for (HostControllerExecutionSupport step : steps) {
                    ModelNode stepNode = step.getDomainOperation();
                    if (stepNode != null) {
                        domainSteps.add(stepNode);
                    }
                }
                if (domainSteps.isEmpty()) {
                    //Nothing to do, return null
                    return null;
                }
                //
                ModelNode stepsParam = new ModelNode();
                for (ModelNode stepNode : domainSteps) {
                    stepsParam.add(stepNode);
                }

                ModelNode result = Util.getEmptyOperation(COMPOSITE, new ModelNode());
                result.get(STEPS).set(stepsParam);
                return result;
            }

            @Override
            public ModelNode getFormattedDomainResult(ModelNode resultNode) {
                ModelNode allSteps = new ModelNode();
                int resultStep = 0;
                for (int i = 0; i < steps.size(); i++) {
                    HostControllerExecutionSupport po = steps.get(i);
                    if (po.getDomainOperation() != null) {
                        String label = "step-" + (++resultStep);
                        ModelNode stepResponseNode = resultNode.get(label);
                        ModelNode formattedStepResponseNode;
                        if (po instanceof MultiStepOpExecutionSupport) {
                            formattedStepResponseNode = stepResponseNode.clone();
                            ModelNode stepResultNode = stepResponseNode.get(RESULT);
                            formattedStepResponseNode.get(RESULT).set(po.getFormattedDomainResult(stepResultNode));
                        } else {
                            formattedStepResponseNode = po.getFormattedDomainResult(stepResponseNode);
                        }
                        allSteps.get("step-" + (i + 1)).set(formattedStepResponseNode);
                    }
                    else {
                        allSteps.get("step-" + (i + 1), OUTCOME).set(IGNORED);
                    }
                }
                return allSteps;
            }
        }
    }
}
