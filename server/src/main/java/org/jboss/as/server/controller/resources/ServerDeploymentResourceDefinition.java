/*
* JBoss, Home of Professional Open Source.
* Copyright 2011, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.as.server.controller.resources;

import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry.Flag;
import org.jboss.as.repository.ContentRepository;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.server.deployment.DeploymentListModulesHandler;
import org.jboss.as.server.deployment.ExplodedDeploymentAddContentHandler;
import org.jboss.as.server.deployment.DeploymentAddHandler;
import org.jboss.as.server.deployment.DeploymentDeployHandler;
import org.jboss.as.server.deployment.DeploymentExplodeHandler;
import org.jboss.as.server.deployment.DeploymentRedeployHandler;
import org.jboss.as.server.deployment.DeploymentRemoveHandler;
import org.jboss.as.server.deployment.DeploymentUndeployHandler;
import org.jboss.as.server.deployment.ManagedDeploymentBrowseContentHandler;
import org.jboss.as.server.deployment.ManagedDeploymentReadContentHandler;
import org.jboss.as.server.deployment.ExplodedDeploymentRemoveContentHandler;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
public class ServerDeploymentResourceDefinition extends DeploymentResourceDefinition {

    private final ContentRepository contentRepository;
    private final ServerEnvironment serverEnvironment;

    private ServerDeploymentResourceDefinition(ContentRepository contentRepository,
                                               ServerEnvironment serverEnvironment, OperationStepHandler addHandler,
                                               OperationStepHandler removeHandler) {
        super(DeploymentResourceParent.SERVER, addHandler, removeHandler);
        this.contentRepository = contentRepository;
        this.serverEnvironment = serverEnvironment;
    }

    public static ServerDeploymentResourceDefinition create(ContentRepository contentRepository, ServerEnvironment serverEnvironment) {
        return new ServerDeploymentResourceDefinition(contentRepository, serverEnvironment,
                DeploymentAddHandler.create(contentRepository),
                new DeploymentRemoveHandler(contentRepository));
    }

    @Override
    public void registerOperations(ManagementResourceRegistration resourceRegistration) {
        super.registerOperations(resourceRegistration);
        resourceRegistration.registerOperationHandler(DeploymentAttributes.DEPLOY_DEFINITION, new DeploymentDeployHandler());
        resourceRegistration.registerOperationHandler(DeploymentAttributes.UNDEPLOY_DEFINITION, new DeploymentUndeployHandler());
        resourceRegistration.registerOperationHandler(DeploymentAttributes.REDEPLOY_DEFINITION, new DeploymentRedeployHandler());
        resourceRegistration.registerOperationHandler(DeploymentAttributes.EXPLODE_DEFINITION, new DeploymentExplodeHandler(contentRepository));
        resourceRegistration.registerOperationHandler(DeploymentAttributes.DEPLOYMENT_ADD_CONTENT_DEFINITION, new ExplodedDeploymentAddContentHandler(contentRepository, serverEnvironment));
        resourceRegistration.registerOperationHandler(DeploymentAttributes.DEPLOYMENT_REMOVE_CONTENT_DEFINITION, new ExplodedDeploymentRemoveContentHandler(contentRepository, serverEnvironment));
        resourceRegistration.registerOperationHandler(DeploymentAttributes.DEPLOYMENT_READ_CONTENT_DEFINITION, new ManagedDeploymentReadContentHandler(contentRepository));
        resourceRegistration.registerOperationHandler(DeploymentAttributes.DEPLOYMENT_BROWSE_CONTENT_DEFINITION, new ManagedDeploymentBrowseContentHandler(contentRepository));
        resourceRegistration.registerOperationHandler(DeploymentAttributes.LIST_MODULES, new DeploymentListModulesHandler());
    }

    @Override
    protected void registerAddOperation(ManagementResourceRegistration registration, OperationStepHandler handler, Flag... flags) {
        registration.registerOperationHandler(DeploymentAttributes.SERVER_DEPLOYMENT_ADD_DEFINITION, handler);
    }
}
