/*
 *
 *  JBoss, Home of Professional Open Source.
 *  Copyright 2014, Red Hat, Inc., and individual contributors
 *  as indicated by the @author tags. See the copyright.txt file in the
 *  distribution for a full listing of individual contributors.
 *
 *  This is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as
 *  published by the Free Software Foundation; either version 2.1 of
 *  the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this software; if not, write to the Free
 *  Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 *  02110-1301 USA, or see the FSF site: http://www.fsf.org.
 * /
 */

package org.jboss.as.test.integration.domain.extension;


import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ReloadRequiredRemoveStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.access.constraint.ApplicationTypeConfig;
import org.jboss.as.controller.access.constraint.SensitivityClassification;
import org.jboss.as.controller.access.management.ApplicationTypeAccessConstraintDefinition;
import org.jboss.as.controller.access.management.SensitiveTargetAccessConstraintDefinition;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * @author Tomaz Cerar (c) 2014 Red Hat Inc.
 */
public class ConstrainedResource extends SimpleResourceDefinition {
    static final SensitivityClassification DS_SECURITY = new SensitivityClassification("datasources", "data-source-security", false, true, true);
    static final SensitiveTargetAccessConstraintDefinition DS_SECURITY_DEF = new SensitiveTargetAccessConstraintDefinition(DS_SECURITY);

    private static final SimpleAttributeDefinition PASSWORD = new SimpleAttributeDefinitionBuilder("password", ModelType.STRING)
            .setAllowExpression(true)
            .addAccessConstraint(SensitiveTargetAccessConstraintDefinition.CREDENTIAL)
            .addAccessConstraint(DS_SECURITY_DEF)
            .build();


    static SimpleAttributeDefinition SECURITY_DOMAIN = new SimpleAttributeDefinitionBuilder("security-domain", ModelType.STRING)
            .setAllowExpression(true)
            .setRequired(false)
            .addAccessConstraint(SensitiveTargetAccessConstraintDefinition.SECURITY_DOMAIN_REF)
            .addAccessConstraint(DS_SECURITY_DEF)
            .build();

    static final SimpleAttributeDefinition AUTHENTICATION_INFLOW = new SimpleAttributeDefinitionBuilder("authentication-inflow", ModelType.BOOLEAN)
            .setRequired(false)
            .setAllowExpression(true)
            .setDefaultValue(ModelNode.FALSE)
            .setNullSignificant(false)
            .addAccessConstraint(DS_SECURITY_DEF)
            .build();


    public ConstrainedResource(PathElement pathElement) {
        super(new Parameters(pathElement, NonResolvingResourceDescriptionResolver.INSTANCE)
                .setAddHandler(new AbstractAddStepHandler(PASSWORD, SECURITY_DOMAIN, AUTHENTICATION_INFLOW))
                .setRemoveHandler(ReloadRequiredRemoveStepHandler.INSTANCE)
                .setAccessConstraints(new ApplicationTypeAccessConstraintDefinition(new ApplicationTypeConfig("datasources", "datasource"))));
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        super.registerAttributes(resourceRegistration);
        resourceRegistration.registerReadOnlyAttribute(PASSWORD, null);
        resourceRegistration.registerReadOnlyAttribute(SECURITY_DOMAIN, null);
        resourceRegistration.registerReadOnlyAttribute(AUTHENTICATION_INFLOW, null);
    }
}
