/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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

package org.jboss.as.controller.access.permission;

import java.security.Permission;
import java.security.PermissionCollection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.access.Action;
import org.jboss.as.controller.access.AuthorizationResult;
import org.jboss.as.controller.access.Authorizer;
import org.jboss.as.controller.access.Environment;
import org.jboss.as.controller.access.JmxAction;
import org.jboss.as.controller.access.JmxTarget;
import org.jboss.as.controller.access.TargetAttribute;
import org.jboss.as.controller.access.TargetResource;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.dmr.ModelNode;
import org.wildfly.security.auth.server.SecurityIdentity;

/**
 * {@link Authorizer} based on {@link ManagementPermission}s configured by a {@link PermissionFactory}.
 *
 * @author Brian Stansberry (c) 2013 Red Hat Inc.
 */
public class ManagementPermissionAuthorizer implements Authorizer {
    private final PermissionFactory permissionFactory;

    public ManagementPermissionAuthorizer(PermissionFactory permissionFactory) {
        this.permissionFactory = permissionFactory;
    }

    @Override
    public AuthorizerDescription getDescription() {
        // We go ahead and create this each time because we expect this to be overridden anyway
        return new AuthorizerDescription() {
            @Override
            public boolean isRoleBased() {
                return true;
            }

            @Override
            public Set<String> getStandardRoles() {
                return Collections.emptySet();
            }
        };
    }

    @Override
    public AuthorizationResult authorize(SecurityIdentity identity, Environment callEnvironment, Action action, TargetAttribute target) {
        assert assertSameAddress(action, target.getTargetResource());
        if (isServerBooting(callEnvironment)) {
            return AuthorizationResult.PERMITTED;
        }
        PermissionCollection userPerms = permissionFactory.getUserPermissions(identity, callEnvironment, action, target);
        PermissionCollection requiredPerms = permissionFactory.getRequiredPermissions(action, target);
        return authorize(userPerms, requiredPerms);
    }

    @Override
    public AuthorizationResult authorize(SecurityIdentity identity, Environment callEnvironment, Action action, TargetResource target) {
        assert assertSameAddress(action, target);
        if (isServerBooting(callEnvironment)) {
            return AuthorizationResult.PERMITTED;
        }
        PermissionCollection userPerms = permissionFactory.getUserPermissions(identity, callEnvironment, action, target);
        if (userPerms == AllPermissionsCollection.INSTANCE) {
            return AuthorizationResult.PERMITTED;
        }
        PermissionCollection requiredPerms = permissionFactory.getRequiredPermissions(action, target);
        return authorize(userPerms, requiredPerms);
    }

    private static boolean assertSameAddress(Action action, TargetResource target) {
        ModelNode operation = action.getOperation();
        // operation can be null in some unit tests; to be lazy ignore those cases
        return operation == null || target.getResourceAddress().equals(PathAddress.pathAddress(operation.get(ModelDescriptionConstants.OP_ADDR)));
    }

    private AuthorizationResult authorize(PermissionCollection userPermissions, PermissionCollection requiredPermissions) {

        final Enumeration<Permission> enumeration = requiredPermissions.elements();
        while (enumeration.hasMoreElements()){
            Permission requiredPermission = enumeration.nextElement();
            if (!userPermissions.implies(requiredPermission)) {
                return new AuthorizationResult(AuthorizationResult.Decision.DENY,
                            new ModelNode(ControllerLogger.ROOT_LOGGER.permissionDenied()));
            }
        }
        return AuthorizationResult.PERMITTED;
    }

    @Override
    public AuthorizationResult authorizeJmxOperation(SecurityIdentity identity, Environment callEnvironment, JmxAction action, JmxTarget target) {
        if (isServerBooting(callEnvironment)) {
            return AuthorizationResult.PERMITTED;
        }
        PermissionCollection userPerms = permissionFactory.getUserPermissions(identity, callEnvironment, action, target);
        PermissionCollection requiredPerms = permissionFactory.getRequiredPermissions(action, target);
        return authorize(userPerms, requiredPerms);
    }

    @Override
    public Set<String> getCallerRoles(SecurityIdentity identity, Environment callEnvironment, Set<String> runAsRoles) {
        // Not supported in this base class; see StandardRBACAuthorizer
        return null;
    }

    private boolean isServerBooting(Environment callEnvironment) {
        return callEnvironment != null && callEnvironment.getProcessState() == ControlledProcessState.State.STARTING;
    }
}
