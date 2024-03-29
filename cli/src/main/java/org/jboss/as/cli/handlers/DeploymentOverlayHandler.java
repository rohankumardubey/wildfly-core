/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
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
package org.jboss.as.cli.handlers;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineCompleter;
import org.jboss.as.cli.CommandLineException;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.accesscontrol.AccessRequirement;
import org.jboss.as.cli.accesscontrol.AccessRequirementBuilder;
import org.jboss.as.cli.impl.ArgumentWithListValue;
import org.jboss.as.cli.impl.ArgumentWithValue;
import org.jboss.as.cli.impl.ArgumentWithoutValue;
import org.jboss.as.cli.impl.CommaSeparatedCompleter;
import org.jboss.as.cli.impl.DefaultCompleter;
import org.jboss.as.cli.impl.DefaultCompleter.CandidatesProvider;
import org.jboss.as.cli.impl.PermittedCandidates;
import org.jboss.as.cli.operation.ParsedCommandLine;
import org.jboss.as.cli.parsing.ExpressionBaseState;
import org.jboss.as.cli.parsing.ParsingState;
import org.jboss.as.cli.parsing.WordCharacterHandler;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;


/**
 *
 * @author Alexey Loubyansky
 */
public class DeploymentOverlayHandler extends BatchModeCommandHandler {//CommandHandlerWithHelp {

    private static final String ADD = "add";
    private static final String LINK = "link";
    private static final String LIST_CONTENT = "list-content";
    private static final String LIST_LINKS = "list-links";
    private static final String REDEPLOY_AFFECTED = "redeploy-affected";
    private static final String REMOVE = "remove";
    private static final String UPLOAD = "upload";

    private static final String WARN_MSG = "WARNING: redeployment is required on deployment ";

    private final ArgumentWithoutValue l;
    private final ArgumentWithValue action;
    private final ArgumentWithValue name;
    private final ArgumentWithListValue content;
    private final ArgumentWithListValue serverGroups;
    private final ArgumentWithoutValue allServerGroups;
    private final ArgumentWithoutValue allRelevantServerGroups;
    private final ArgumentWithListValue deployments;
    private final ArgumentWithoutValue redeployAffected;

    private final FilenameTabCompleter pathCompleter;

    private AccessRequirement generalListPermission;
    private AccessRequirement addPermission;
    private AccessRequirement linkPermission;
    private AccessRequirement removePermission;
    private AccessRequirement redeployPermission;
    private AccessRequirement listContentPermission;
    private AccessRequirement listLinksPermission;

    public DeploymentOverlayHandler(CommandContext ctx) {
        super(ctx, "deployment-overlay", true);

        l = new ArgumentWithoutValue(this, "-l") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                final String actionStr = action.getValue(ctx.getParsedCommandLine());
                if(actionStr == null || LIST_CONTENT.equals(actionStr) || LIST_LINKS.equals(actionStr)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        l.setAccessRequirement(generalListPermission);

        action = new ArgumentWithValue(this, new DefaultCompleter(
                PermittedCandidates.create(ADD, addPermission)
                .add(LINK, linkPermission)
                .add(LIST_CONTENT, listContentPermission)
                .add(LIST_LINKS, listLinksPermission)
                .add(REDEPLOY_AFFECTED, redeployPermission)
                .add(REMOVE, removePermission)
                .add(UPLOAD, addPermission)),
                0, "--action");

        name = new ArgumentWithValue(this, new DefaultCompleter(new CandidatesProvider(){
            @Override
            public Collection<String> getAllCandidates(CommandContext ctx) {
                final ModelControllerClient client = ctx.getModelControllerClient();
                if(client == null) {
                    return Collections.emptyList();
                }
                final ModelNode op = new ModelNode();
                op.get(Util.OPERATION).set(Util.READ_CHILDREN_NAMES);
                op.get(Util.ADDRESS).setEmptyList();
                op.get(Util.CHILD_TYPE).set(Util.DEPLOYMENT_OVERLAY);
                final ModelNode response;
                try {
                    response = client.execute(op);
                } catch (IOException e) {
                    return Collections.emptyList();
                }
                final ModelNode result = response.get(Util.RESULT);
                if(!result.isDefined()) {
                    return Collections.emptyList();
                }
                final List<String> names = new ArrayList<String>();
                for(ModelNode node : result.asList()) {
                    names.add(node.asString());
                }
                return names;
            }}), "--name");
        name.addRequiredPreceding(action);
        name.setAccessRequirement(AccessRequirementBuilder.Factory.create(ctx).any()
                .requirement(addPermission)
                .requirement(removePermission)
                .requirement(linkPermission)
                .requirement(listContentPermission)
                .requirement(listLinksPermission)
                .requirement(redeployPermission)
                .build());

        pathCompleter = FilenameTabCompleter.newCompleter(ctx);
        content = new ArgumentWithListValue(this, new CommandLineCompleter(){
            @Override
            public int complete(CommandContext ctx, String buffer, int cursor, List<String> candidates) {
                final String actionStr = action.getValue(ctx.getParsedCommandLine());
                if (ADD.equals(actionStr) || UPLOAD.equals(actionStr)) {
                    // TODO add support for quoted paths
                    int i = buffer.lastIndexOf(',');
                    i = buffer.indexOf('=', i + 1);
                    if (i < 0) {
                        return -1;
                    }
                    final String path = buffer.substring(i + 1);
                    int pathResult = pathCompleter.complete(ctx, path, path.length(), candidates);
                    if (pathResult < 0) {
                        return -1;
                    }
                    return i + 1 + pathResult;
                } else if(REMOVE.equals(actionStr)) {
                    final String nameStr = name.getValue(ctx.getParsedCommandLine());
                    if(nameStr == null) {
                        return -1;
                    }
                    final List<String> existing;
                    try {
                        existing = loadContentFor(ctx.getModelControllerClient(), nameStr);
                    } catch (CommandLineException e) {
                        return -1;
                    }
                    if(existing.isEmpty()) {
                        return buffer.length();
                    }
                    candidates.addAll(existing);
                    if(buffer.isEmpty()) {
                        return 0;
                    }
                    final String[] specified = buffer.split(",+");
                    candidates.removeAll(Arrays.asList(specified));
                    if(buffer.charAt(buffer.length() - 1) == ',') {
                        return buffer.length();
                    }
                    final String chunk = specified[specified.length - 1];
                    for(int i = 0; i < candidates.size(); ++i) {
                        if(!candidates.get(i).startsWith(chunk)) {
                            candidates.remove(i);
                        }
                    }
                    return buffer.length() - chunk.length();
                } else {
                    return -1;
                }
            }}, "--content") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                final String actionStr = action.getValue(ctx.getParsedCommandLine());
                if(actionStr == null) {
                    return false;
                }
                if(ADD.equals(actionStr) || UPLOAD.equals(actionStr) || REMOVE.equals(actionStr)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
            @Override
            protected ParsingState initParsingState() {
                final ExpressionBaseState state = new ExpressionBaseState("EXPR", true, false);
                if(Util.isWindows()) {
                    // to not require escaping FS name separator
                    state.setDefaultHandler(WordCharacterHandler.IGNORE_LB_ESCAPE_OFF);
                } else {
                    state.setDefaultHandler(WordCharacterHandler.IGNORE_LB_ESCAPE_ON);
                }
                return state;
            }
        };
        content.addRequiredPreceding(name);
        content.addCantAppearAfter(l);

        serverGroups = new ArgumentWithListValue(this, new CommaSeparatedCompleter() {
            @Override
            protected Collection<String> getAllCandidates(CommandContext ctx) {
                return Util.getServerGroups(ctx.getModelControllerClient());
            }} , "--server-groups") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(!ctx.isDomainMode()) {
                    return false;
                }
                final String actionStr = action.getValue(ctx.getParsedCommandLine());
                if(actionStr == null) {
                    return false;
                }
                if(ADD.equals(actionStr) || LINK.equals(actionStr)
                        || REMOVE.equals(actionStr) || LIST_LINKS.equals(actionStr)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        serverGroups.addRequiredPreceding(name);

        allRelevantServerGroups = new ArgumentWithoutValue(this, "--all-relevant-server-groups") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(!ctx.isDomainMode()) {
                    return false;
                }
                final String actionStr = action.getValue(ctx.getParsedCommandLine());
                if(actionStr == null) {
                    return false;
                }
                if(REMOVE.equals(actionStr)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        allRelevantServerGroups.addRequiredPreceding(name);
        allRelevantServerGroups.addCantAppearAfter(serverGroups);
        serverGroups.addCantAppearAfter(allRelevantServerGroups);

        allServerGroups = new ArgumentWithoutValue(this, "--all-server-groups") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(!ctx.isDomainMode()) {
                    return false;
                }
                final String actionStr = action.getValue(ctx.getParsedCommandLine());
                if(actionStr == null) {
                    return false;
                }
                if(ADD.equals(actionStr) || LINK.equals(actionStr)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        allServerGroups.addRequiredPreceding(name);
        allServerGroups.addCantAppearAfter(serverGroups);
        serverGroups.addCantAppearAfter(allServerGroups);

        deployments = new ArgumentWithListValue(this, new CommaSeparatedCompleter() {
            @Override
            protected Collection<String> getAllCandidates(CommandContext ctx) {
                final String actionValue = action.getValue(ctx.getParsedCommandLine());
                final ModelControllerClient client = ctx.getModelControllerClient();
                if(REMOVE.equals(actionValue)) {
                    final String overlay = name.getValue(ctx.getParsedCommandLine());
                    if(overlay == null) {
                        return Collections.emptyList();
                    }
                    try {
                        if(ctx.isDomainMode()) {
                            final String groupsStr = serverGroups.getValue(ctx.getParsedCommandLine());
                            if(groupsStr != null) {
                                final String[] groups = groupsStr.split(",+");
                                if(groups.length == 1) {
                                    return filterLinks(loadLinkResources(client, overlay, groups[0]));
                                } else if(groups.length > 1) {
                                    final Set<String> commonLinks = new HashSet<String>();
                                    commonLinks.addAll(filterLinks(loadLinkResources(client, overlay, groups[0])));
                                    for(int i = 1; i < groups.length; ++i) {
                                        commonLinks.retainAll(filterLinks(loadLinkResources(client, overlay, groups[i])));
                                    }
                                    return commonLinks;
                                }
                            }
                        } else {
                            return filterLinks(loadLinkResources(client, overlay, null));
                        }
                    } catch(CommandLineException e) {
                    }
                    return Collections.emptyList();
                }
                return Util.getDeploymentRuntimeNames(client);
            }}, "--deployments") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(ctx.isDomainMode()) {
                    if(serverGroups.isPresent(ctx.getParsedCommandLine()) || allServerGroups.isPresent(ctx.getParsedCommandLine())) {
                        return super.canAppearNext(ctx);
                    }
                    return false;
                }
                final String actionStr = action.getValue(ctx.getParsedCommandLine());
                if(actionStr == null) {
                    return false;
                }
                if(ADD.equals(actionStr) || LINK.equals(actionStr) || REMOVE.equals(actionStr)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        deployments.addRequiredPreceding(name);
        deployments.addCantAppearAfter(l);

        redeployAffected = new ArgumentWithoutValue(this, "--redeploy-affected") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(deployments.isPresent(ctx.getParsedCommandLine())) {
                    return super.canAppearNext(ctx);
                }
                final String actionValue = action.getValue(ctx.getParsedCommandLine());
                if(actionValue != null && (actionValue.equals(UPLOAD) || actionValue.equals(REMOVE))) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        redeployAffected.addRequiredPreceding(name);
        redeployAffected.setAccessRequirement(redeployPermission);
    }

    @Override
    protected AccessRequirement setupAccessRequirement(CommandContext ctx) {

        generalListPermission = AccessRequirementBuilder.Factory.create(ctx).any()
                .operation(Util.DEPLOYMENT_OVERLAY + "=?", Util.READ_CHILDREN_NAMES).build();

        addPermission = AccessRequirementBuilder.Factory.create(ctx)
                .any()
                .operation(Util.DEPLOYMENT_OVERLAY + "=?", Util.ADD)
                .operation(Util.DEPLOYMENT_OVERLAY + "=?/" + Util.CONTENT + "=?", Util.ADD)
                .build();

        linkPermission = AccessRequirementBuilder.Factory.create(ctx)
                .any()
                .operation(Util.DEPLOYMENT_OVERLAY + "=?/" + Util.DEPLOYMENT + "=?", Util.ADD)
                .serverGroupOperation(Util.DEPLOYMENT_OVERLAY + "=?", Util.ADD)
                .serverGroupOperation(Util.DEPLOYMENT_OVERLAY + "=?/" + Util.DEPLOYMENT + "=?", Util.ADD)
                .build();

        removePermission = AccessRequirementBuilder.Factory.create(ctx)
                .any()
                .operation(Util.DEPLOYMENT_OVERLAY + "=?", Util.REMOVE)
                .operation(Util.DEPLOYMENT_OVERLAY + "=?/" + Util.CONTENT + "=?", Util.REMOVE)
                .operation(Util.DEPLOYMENT_OVERLAY + "=?/" + Util.DEPLOYMENT + "=?", Util.REMOVE)
                .all()
                .serverGroupOperation(Util.DEPLOYMENT_OVERLAY + "=?/" + Util.DEPLOYMENT + "=?", Util.REMOVE)
                .serverGroupOperation(Util.DEPLOYMENT_OVERLAY + "=?", Util.REMOVE)
                .parent()
                .build();

        redeployPermission = AccessRequirementBuilder.Factory.create(ctx).any()
                .operation(Util.DEPLOYMENT_OVERLAY + "=?", Util.REDEPLOY_LINKS)
                .serverGroupOperation(Util.DEPLOYMENT_OVERLAY + "=?", Util.REDEPLOY_LINKS + "a")
                .build();

        listContentPermission = AccessRequirementBuilder.Factory.create(ctx).any()
                .operation(Util.DEPLOYMENT_OVERLAY + "=?", Util.READ_CHILDREN_NAMES)
                .build();

        listLinksPermission = AccessRequirementBuilder.Factory.create(ctx)
                .any()
                .operation(Util.DEPLOYMENT_OVERLAY + "=?", Util.READ_CHILDREN_NAMES)
                .serverGroupOperation(Util.DEPLOYMENT_OVERLAY + "=?", Util.READ_CHILDREN_NAMES)
                .build();

        return AccessRequirementBuilder.Factory.create(ctx)
                .any()
                .requirement(generalListPermission)
                .requirement(addPermission)
                .requirement(linkPermission)
                .requirement(removePermission)
                .requirement(redeployPermission)
                .requirement(listContentPermission)
                .requirement(listLinksPermission)
                .build();
    }

    @Override
    public boolean isBatchMode(CommandContext ctx) {
        final String action = this.action.getValue(ctx.getParsedCommandLine());
        if(action != null && (LIST_LINKS.equals(action) || LIST_CONTENT.equals(action))) {
            return false;
        }
        return super.isBatchMode(ctx);
    }

    @Override
    protected void doHandle(CommandContext ctx) throws CommandLineException {

        final ParsedCommandLine args = ctx.getParsedCommandLine();
        if(!args.hasProperties() || l.isPresent(args) && args.getOtherProperties().isEmpty() && args.getPropertyNames().size() == 1) {
            // list registered overlays
            final ModelNode op = new ModelNode();
            op.get(Util.ADDRESS).setEmptyList();
            op.get(Util.OPERATION).set(Util.READ_CHILDREN_NAMES);
            op.get(Util.CHILD_TYPE).set(Util.DEPLOYMENT_OVERLAY);
            final ModelNode response;
            try {
                response = ctx.getModelControllerClient().execute(op);
            } catch (IOException e) {
                throw new CommandLineException("Failed to execute " + Util.READ_CHILDREN_NAMES, e);
            }
            final ModelNode result = response.get(Util.RESULT);
            if(!result.isDefined()) {
                final String descr = Util.getFailureDescription(response);
                if(descr != null) {
                    throw new CommandLineException(descr);
                }
                throw new CommandLineException("The response of " + Util.READ_CHILDREN_NAMES + " is missing result: " + response);
            }

            if(l.isPresent(args)) {
                for(ModelNode node : result.asList()) {
                    ctx.printLine(node.asString());
                }
            } else {
                final List<String> names = new ArrayList<String>();
                for(ModelNode node : result.asList()) {
                    names.add(node.asString());
                }
                ctx.printColumns(names);
            }
            return;
        }

        final String action = this.action.getValue(args, true);
        if(ADD.equals(action)) {
            add(ctx, true);
        } else if(UPLOAD.equals(action)) {
            upload(ctx, true);
        } else if(LIST_CONTENT.equals(action)) {
            listContent(ctx);
        } else if(LIST_LINKS.equals(action)) {
            listLinks(ctx);
        } else {
            super.doHandle(ctx);
        }
    }

    @Override
    protected ModelNode buildRequestWithoutHeaders(CommandContext ctx) throws CommandFormatException {
        final ParsedCommandLine args = ctx.getParsedCommandLine();
        final String action = this.action.getValue(args, true);
        try {
            if (REMOVE.equals(action)) {
                return remove(ctx);
            } else if (LINK.equals(action)) {
                return link(ctx);
            } else if (REDEPLOY_AFFECTED.equals(action)) {
                return redeployAffected(ctx);
            } else if (ADD.equals(action)) {
                return add(ctx, false);
            } else if (UPLOAD.equals(action)) {
                return upload(ctx, false);
            } else {
                throw new CommandFormatException("Doesn't know how to build request for action '" + action + "'");
            }
        } catch (CommandFormatException e) {
            throw e;
        } catch (CommandLineException e) {
            throw new CommandFormatException("Failed to build " + action + " request.", e);
        }
    }

    protected ModelNode redeployAffected(CommandContext ctx) throws CommandLineException {
        final ParsedCommandLine args = ctx.getParsedCommandLine();
        assertNotPresent(serverGroups, args);
        assertNotPresent(allServerGroups, args);
        assertNotPresent(allRelevantServerGroups, args);
        assertNotPresent(content, args);
        assertNotPresent(deployments, args);
        assertNotPresent(redeployAffected, args);

        final String overlay = getName(ctx, false);

        final ModelNode redeployOp = new ModelNode();
        redeployOp.get(Util.OPERATION).set(Util.COMPOSITE);
        redeployOp.get(Util.ADDRESS).setEmptyList();
        final ModelNode steps = redeployOp.get(Util.STEPS);

        // In domain and standalone, this operation redeploy them all.
        addRedeployStep(overlay, steps);

        if(!steps.isDefined() || steps.asList().isEmpty()) {
            throw new CommandFormatException("None of the deployments affected.");
        }
        return redeployOp;
    }

    /**
     * Validate that the overlay exists. If it doesn't exist, throws an
     * exception if not in batch mode or if failInBatch is true. In batch mode,
     * we could be in the case that the overlay doesn't exist yet.
     */
    private String getName(CommandContext ctx, boolean failInBatch) throws CommandLineException {
        final ParsedCommandLine args = ctx.getParsedCommandLine();
        final String name = this.name.getValue(args, true);
        if (name == null) {
            throw new CommandFormatException(this.name + " is missing value.");
        }
        if (!ctx.isBatchMode() || failInBatch) {
            if (!Util.isValidPath(ctx.getModelControllerClient(), Util.DEPLOYMENT_OVERLAY, name)) {
                throw new CommandFormatException("Deployment overlay " + name + " does not exist.");
            }
        }
        return name;
    }

    protected void listLinks(CommandContext ctx) throws CommandLineException {

        final ModelControllerClient client = ctx.getModelControllerClient();
        final ParsedCommandLine args = ctx.getParsedCommandLine();
        assertNotPresent(allRelevantServerGroups, args);
        assertNotPresent(content, args);
        assertNotPresent(deployments, args);
        assertNotPresent(redeployAffected, args);

        final String name = getName(ctx, true);

        final String sg = serverGroups.getValue(ctx.getParsedCommandLine());
        if(ctx.isDomainMode()) {
            final List<String> groups;
            if(sg == null) {
                //throw new CommandFormatException(serverGroups.getFullName() + " is missing value.");
                groups = Util.getServerGroups(client);
            } else {
                groups = Arrays.asList(sg.split(",+"));
            }
            if(groups.isEmpty()) {
                throw new CommandFormatException(serverGroups.getFullName() + " is missing value.");
            }
            for(String group : groups) {
                final List<String> links = loadLinks(client, name, group);
                if(!links.isEmpty()) {
                    ctx.printLine("SERVER GROUP: " + group + Util.LINE_SEPARATOR);
                    ctx.printColumns(links);
                    ctx.printLine("");
                }
            }
        } else {
            final List<String> content = loadLinks(client, name, sg);
            if (l.isPresent(args)) {
                for (String contentPath : content) {
                    ctx.printLine(contentPath);
                }
            } else {
                ctx.printColumns(content);
            }
        }
    }

    protected void listContent(CommandContext ctx) throws CommandLineException {

        final ModelControllerClient client = ctx.getModelControllerClient();
        final ParsedCommandLine args = ctx.getParsedCommandLine();
        assertNotPresent(serverGroups, args);
        assertNotPresent(allServerGroups, args);
        assertNotPresent(allRelevantServerGroups, args);
        assertNotPresent(deployments, args);
        assertNotPresent(content, args);
        assertNotPresent(redeployAffected, args);

        final String name = getName(ctx, true);
        final List<String> content = loadContentFor(client, name);
        if(l.isPresent(args)) {
            for(String contentPath : content) {
                ctx.printLine(contentPath);
            }
        } else {
            ctx.printColumns(content);
        }
    }

    protected ModelNode remove(CommandContext ctx) throws CommandLineException {

        final ModelControllerClient client = ctx.getModelControllerClient();

        final ParsedCommandLine args = ctx.getParsedCommandLine();
        assertNotPresent(allServerGroups, args);

        final String name = getName(ctx, false);
        final String contentStr = content.getValue(args);
        String deploymentStr = deployments.getValue(args);
        final String sgStr = serverGroups.getValue(args);
        final List<String> sg;
        if(sgStr == null) {
            if(allRelevantServerGroups.isPresent(args)) {
                sg = Util.getServerGroupsReferencingOverlay(name, client);
            } else {
                sg = null;
            }
        } else {
            sg = Arrays.asList(sgStr.split(",+"));
            if(sg.isEmpty()) {
                throw new CommandFormatException(serverGroups.getFullName() + " is missing value.");
            }
        }

        final ModelNode composite = new ModelNode();
        composite.get(Util.OPERATION).set(Util.COMPOSITE);
        composite.get(Util.ADDRESS).setEmptyList();
        final ModelNode steps = composite.get(Util.STEPS);

        boolean redeploy = this.redeployAffected.isPresent(args);

        if (!redeployAffected.isPresent(args)) {
            printWarning(ctx, client, name, contentStr, deploymentStr);
        }

        // 1) Remove the overlay fully.
        // If redeploy, retrieve all links and remove then with redeploy-affected flag
        // If no serverGroup provided in domainMode, remove also overlay in root.
        if (contentStr == null && deploymentStr == null) {
            if (ctx.isDomainMode()) {
                final List<String> groups = sg == null ? Util.getServerGroupsReferencingOverlay(name, client) : sg;
                for (String group : groups) {
                    if (redeploy) {
                        List<String> links = loadLinks(client, name, group);
                        for (String link : links) {
                            addRemoveDeploymentStep(name, group, redeploy, link, steps);
                        }
                    }
                    addRemoveOverlayStep(name, group, steps);
                }
                // Remove the root if no sgroup provided
                if (sg == null) {
                    addRemoveOverlayStep(name, null, steps);
                }
            } else {
                if (redeploy) {
                    List<String> links = loadLinks(client, name, null);
                    for (String link : links) {
                        addRemoveDeploymentStep(name, null, redeploy, link, steps);
                    }
                }
                addRemoveOverlayStep(name, null, steps);
            }
            return composite;
        }

        boolean redeployAll = false;
        // 2) Remove some content.
        if (contentStr != null) {
            // If redeploy required, must redeploy them all.
            redeployAll = redeploy && true;
            final List<String> contentList;
            contentList = java.util.Arrays.asList(contentStr.split(",+"));

            for (String content : contentList) {
                final ModelNode op = new ModelNode();
                ModelNode addr = op.get(Util.ADDRESS);
                addr.add(Util.DEPLOYMENT_OVERLAY, name);
                addr.add(Util.CONTENT, content);
                op.get(Util.OPERATION).set(Util.REMOVE);
                steps.add(op);
            }
        }

        // 3) Remove some deployments.
        // If redeploy, then must pass redeploy to each removed deployment
        // remove operation
        if (deploymentStr != null) {
            final List<String> links = Arrays.asList(deploymentStr.split(",+"));
            if (ctx.isDomainMode()) {
                if (sg == null) {
                    throw new CommandFormatException(serverGroups.getFullName() + " or "
                            + allRelevantServerGroups.getFullName() + " is required.");
                }
                for (String group : sg) {
                    for (String link : links) {
                        addRemoveDeploymentStep(name, group, redeploy, link, steps);
                    }
                }
            } else {
                for (String link : links) {
                    addRemoveDeploymentStep(name, null, redeploy, link, steps);
                }
            }
        }

        // Finally redeploy still referenced links.
        if (redeployAll) {
            // In domain and standalone, this operation redeploy them all.
            addRedeployStep(name, steps);
        }
        return composite;
    }

    private void addRedeployStep(String overlay, ModelNode steps) {
        addRedeployStep(overlay, (List<String>) null, null, steps);
    }

    private void addRedeployStep(String overlay, String linkName, String sgName, ModelNode steps) {
        addRedeployStep(overlay, Arrays.asList(linkName), sgName, steps);
    }

    private void addRedeployStep(String overlay, List<String> linkNames, String sgName, ModelNode steps) {
        final ModelNode redeployOp = new ModelNode();
        final ModelNode addr = redeployOp.get(Util.ADDRESS);
        if (sgName != null) {
            addr.add(Util.SERVER_GROUP, sgName);
        }
        addr.add(Util.DEPLOYMENT_OVERLAY, overlay);
        redeployOp.get(Util.OPERATION).set(Util.REDEPLOY_LINKS);
        if (linkNames != null) {
            ModelNode lst = new ModelNode();
            for (String str : linkNames) {
                lst.add(str);
            }
            redeployOp.get(Util.DEPLOYMENTS).set(lst);
        }
        steps.add(redeployOp);
    }

    private void addRemoveOverlayStep(String name, String group, ModelNode steps) {
        final ModelNode op = new ModelNode();
        final ModelNode addr = op.get(Util.ADDRESS);
        if (group != null) {
            addr.add(Util.SERVER_GROUP, group);
        }
        addr.add(Util.DEPLOYMENT_OVERLAY, name);
        op.get(Util.OPERATION).set(Util.REMOVE);
        steps.add(op);
    }

    private void addRemoveDeploymentStep(String name, String group, boolean redeploy, String deployment, ModelNode steps) {
        final ModelNode op = new ModelNode();
        final ModelNode addr = op.get(Util.ADDRESS);
        if (group != null) {
            addr.add(Util.SERVER_GROUP, group);
        }
        addr.add(Util.DEPLOYMENT_OVERLAY, name);
        addr.add(Util.DEPLOYMENT, deployment);
        op.get(Util.OPERATION).set(Util.REMOVE);
        if (redeploy) {
            op.get(Util.REDEPLOY_AFFECTED).set(true);
        }
        steps.add(op);
    }

    // WFCORE-2045 print warning on CLI side when argument '--redeploy-affected' is not specified
    private void printWarning(CommandContext ctx, final ModelControllerClient client, final String name, final String content,
            final String deployment) throws CommandLineException {
        if (ctx.isDomainMode()) {
            for (String sgName : Util.getServerGroups(client)) {
                printWarningMessage(ctx, client, name, content, deployment, sgName);
            }
        } else {
            printWarningMessage(ctx, client, name, content, deployment, null);
        }
    }

    private void printWarningMessage(CommandContext ctx, final ModelControllerClient client, final String name,
            final String content, final String deployment, String sgName) throws CommandLineException {
        if (content == null && deployment != null) { // explicit deployment specified
            printLine(ctx, Arrays.asList(deployment.split(",+")), sgName);
        } else {
            final ModelNode linkResources = loadLinkResources(client, name, sgName);
            if (linkResources != null && !linkResources.keys().isEmpty()) {
                printLine(ctx, linkResources.keys(), sgName);
            }
        }
    }

    private void printLine(CommandContext ctx, final Collection<String> deployment, final String sgName) {
        String message = WARN_MSG + deployment;
        if (sgName == null) {
            ctx.printLine(message);
        } else {
            // domain mode with server group specified.
            ctx.printLine(message + " in server group " + sgName);
        }
    }

    protected ModelNode add(CommandContext ctx, boolean stream) throws CommandLineException {

        final ParsedCommandLine args = ctx.getParsedCommandLine();
        assertNotPresent(allRelevantServerGroups, args);

        final String name = this.name.getValue(args, true);
        final String contentStr = content.getValue(args, true);

        final String[] contentPairs = contentStr.split(",+");
        if(contentPairs.length == 0) {
            throw new CommandFormatException("Overlay content is not specified.");
        }
        final String[] contentNames = new String[contentPairs.length];
        final File[] contentPaths = new File[contentPairs.length];
        for(int i = 0; i < contentPairs.length; ++i) {
            final String pair = contentPairs[i];
            final int equalsIndex = pair.indexOf('=');
            if(equalsIndex < 0) {
                throw new CommandFormatException("Content pair is not following archive-path=fs-path format: '" + pair + "'");
            }
            contentNames[i] = pair.substring(0, equalsIndex);
            if(contentNames[i].length() == 0) {
                throw new CommandFormatException("The archive path is missing for the content '" + pair + "'");
            }
            String path = pair.substring(equalsIndex + 1);
            if(path.length() == 0) {
                throw new CommandFormatException("The filesystem paths is missing for the content '" + pair + "'");
            }
            path = pathCompleter.translatePath(path);
            final File f = new File(path);
            if(!f.exists()) {
                throw new CommandFormatException("Content file doesn't exist " + f.getAbsolutePath());
            }
            contentPaths[i] = f;
        }

        final String[] deployments = getLinks(this.deployments, args);

        final ModelControllerClient client = ctx.getModelControllerClient();

        final ModelNode composite = new ModelNode();
        final OperationBuilder opBuilder = stream ? new OperationBuilder(composite, true) : null;
        composite.get(Util.OPERATION).set(Util.COMPOSITE);
        composite.get(Util.ADDRESS).setEmptyList();
        final ModelNode steps = composite.get(Util.STEPS);

        // add the overlay
        ModelNode op = new ModelNode();
        ModelNode address = op.get(Util.ADDRESS);
        address.add(Util.DEPLOYMENT_OVERLAY, name);
        op.get(Util.OPERATION).set(Util.ADD);
        steps.add(op);

        // add the content
        for (int i = 0; i < contentNames.length; ++i) {
            final String contentName = contentNames[i];
            op = new ModelNode();
            address = op.get(Util.ADDRESS);
            address.add(Util.DEPLOYMENT_OVERLAY, name);
            address.add(Util.CONTENT, contentName);
            op.get(Util.OPERATION).set(Util.ADD);
            if(opBuilder != null) {
                op.get(Util.CONTENT).get(Util.INPUT_STREAM_INDEX).set(i);
                opBuilder.addFileAsAttachment(contentPaths[i]);
            } else {
                op.get(Util.CONTENT).get(Util.BYTES).set(Util.readBytes(contentPaths[i]));
            }
            steps.add(op);
        }

        if(deployments != null) {
            if(ctx.isDomainMode()) {
                final List<String> sg = getServerGroupsToLink(ctx);
                for(String group : sg) {
                    // here we don't need a separate check whether the overlay is linked
                    // from the server group since it is created in the same op.
                    op = new ModelNode();
                    address = op.get(Util.ADDRESS);
                    address.add(Util.SERVER_GROUP, group);
                    address.add(Util.DEPLOYMENT_OVERLAY, name);
                    op.get(Util.OPERATION).set(Util.ADD);
                    steps.add(op);
                    addAddRedeployLinksSteps(ctx, steps, name, group, deployments, false);
                }
            } else {
                addAddRedeployLinksSteps(ctx, steps, name, null, deployments, false);
            }
        } else if(ctx.isDomainMode() && (serverGroups.isPresent(args) || allServerGroups.isPresent(args))) {
            throw new CommandFormatException("server groups are specified but " + this.deployments.getFullName() +
                    " is not.");
        }

        if(opBuilder == null) {
            return composite;
        }

        try {
            final ModelNode result = client.execute(opBuilder.build());
            if (!Util.isSuccess(result)) {
                throw new CommandFormatException(Util.getFailureDescription(result));
            }
        } catch (IOException e) {
            throw new CommandFormatException("Failed to add overlay", e);
        }
        return null;
    }

    protected ModelNode link(CommandContext ctx) throws CommandLineException {

        final ParsedCommandLine args = ctx.getParsedCommandLine();
        assertNotPresent(allRelevantServerGroups, args);

        final String name = getName(ctx, false);
        final String[] deployments = getLinks(this.deployments, args);
        if(deployments == null) {
            throw new CommandFormatException(this.deployments.getFullName() + " is required.");
        }

        final ModelNode composite = new ModelNode();
        composite.get(Util.OPERATION).set(Util.COMPOSITE);
        composite.get(Util.ADDRESS).setEmptyList();
        final ModelNode steps = composite.get(Util.STEPS);

        final ModelControllerClient client = ctx.getModelControllerClient();
        if(ctx.isDomainMode()) {
            final List<String> sg = getServerGroupsToLink(ctx);
            for(String group : sg) {
                if(!Util.isValidPath(client, Util.SERVER_GROUP, group, Util.DEPLOYMENT_OVERLAY, name)) {
                    final ModelNode op = new ModelNode();
                    final ModelNode address = op.get(Util.ADDRESS);
                    address.add(Util.SERVER_GROUP, group);
                    address.add(Util.DEPLOYMENT_OVERLAY, name);
                    op.get(Util.OPERATION).set(Util.ADD);
                    steps.add(op);
                }
                addAddRedeployLinksSteps(ctx, steps, name, group, deployments, false);
            }
        } else {
            addAddRedeployLinksSteps(ctx, steps, name, null, deployments, false);
        }
        return composite;
/*        try {
            final ModelNode result = client.execute(composite);
            if (!Util.isSuccess(result)) {
                throw new CommandFormatException(Util.getFailureDescription(result));
            }
        } catch (IOException e) {
            throw new CommandFormatException("Failed to link overlay", e);
        }
*/    }

    protected ModelNode upload(CommandContext ctx, boolean stream) throws CommandLineException {

        final ParsedCommandLine args = ctx.getParsedCommandLine();

        final String name = getName(ctx, false);
        final String contentStr = content.getValue(args, true);

        final String[] contentPairs = contentStr.split(",+");
        if(contentPairs.length == 0) {
            throw new CommandFormatException("Overlay content is not specified.");
        }
        final String[] contentNames = new String[contentPairs.length];
        final File[] contentPaths = new File[contentPairs.length];
        for(int i = 0; i < contentPairs.length; ++i) {
            final String pair = contentPairs[i];
            final int equalsIndex = pair.indexOf('=');
            if(equalsIndex < 0) {
                throw new CommandFormatException("Content pair is not following archive-path=fs-path format: '" + pair + "'");
            }
            contentNames[i] = pair.substring(0, equalsIndex);
            if(contentNames[i].length() == 0) {
                throw new CommandFormatException("The archive path is missing for the content '" + pair + "'");
            }
            String path = pair.substring(equalsIndex + 1);
            if(path.length() == 0) {
                throw new CommandFormatException("The filesystem paths is missing for the content '" + pair + "'");
            }
            path = pathCompleter.translatePath(path);
            final File f = new File(path);
            if(!f.exists()) {
                throw new CommandFormatException("Content file doesn't exist " + f.getAbsolutePath());
            }
            contentPaths[i] = f;
        }

        final String deploymentsStr = deployments.getValue(args);
        if(deploymentsStr != null) {
            throw new CommandFormatException(deployments.getFullName() + " can't be used in combination with upload.");
        }

        final ModelControllerClient client = ctx.getModelControllerClient();

        final ModelNode composite = new ModelNode();
        final OperationBuilder opBuilder = stream ? new OperationBuilder(composite, true) : null;
        composite.get(Util.OPERATION).set(Util.COMPOSITE);
        composite.get(Util.ADDRESS).setEmptyList();
        final ModelNode steps = composite.get(Util.STEPS);

        // add the content
        for (int i = 0; i < contentNames.length; ++i) {
            final ModelNode op = new ModelNode();
            ModelNode address = op.get(Util.ADDRESS);
            address.add(Util.DEPLOYMENT_OVERLAY, name);
            address.add(Util.CONTENT, contentNames[i]);
            op.get(Util.OPERATION).set(Util.ADD);
            if(opBuilder != null) {
                op.get(Util.CONTENT).get(Util.INPUT_STREAM_INDEX).set(i);
                opBuilder.addFileAsAttachment(contentPaths[i]);
            } else {
                op.get(Util.CONTENT).get(Util.BYTES).set(Util.readBytes(contentPaths[i]));
            }
            steps.add(op);
        }

        if(redeployAffected.isPresent(args)) {
            // In domain and standalone, this operation redeploy them all.
            addRedeployStep(name, steps);
        } else {
            printWarning(ctx, client, name, contentStr, null);
        }

        if(opBuilder == null) {
            return composite;
        }

        try {
            final ModelNode result = client.execute(opBuilder.build());
            if (!Util.isSuccess(result)) {
                throw new CommandFormatException(Util.getFailureDescription(result));
            }
        } catch (IOException e) {
            throw new CommandFormatException("Failed to add overlay", e);
        }
        return null;
    }

    protected List<String> loadContentFor(final ModelControllerClient client, final String overlay) throws CommandLineException {
        final List<String> contentList;
        final ModelNode op = new ModelNode();
        op.get(Util.ADDRESS).add(Util.DEPLOYMENT_OVERLAY, overlay);
        op.get(Util.OPERATION).set(Util.READ_CHILDREN_NAMES);
        op.get(Util.CHILD_TYPE).set(Util.CONTENT);
        final ModelNode response;
        try {
            response = client.execute(op);
        } catch (IOException e) {
            throw new CommandLineException("Failed to load the list of the existing content for overlay " + overlay, e);
        }

        final ModelNode result = response.get(Util.RESULT);
        if(!result.isDefined()) {
            throw new CommandLineException("Failed to load the list of the existing content for overlay " + overlay + ": " + response);
        }
        contentList = new ArrayList<String>();
        for(ModelNode node : result.asList()) {
            contentList.add(node.asString());
        }
        return contentList;
    }

    protected List<String> loadLinks(final ModelControllerClient client, String overlay, String serverGroup) throws CommandLineException {
        final ModelNode op = new ModelNode();
        final ModelNode addr = op.get(Util.ADDRESS);
        if(serverGroup != null) {
            addr.add(Util.SERVER_GROUP, serverGroup);
        }
        addr.add(Util.DEPLOYMENT_OVERLAY, overlay);
        op.get(Util.OPERATION).set(Util.READ_CHILDREN_NAMES);
        op.get(Util.CHILD_TYPE).set(Util.DEPLOYMENT);
        final ModelNode response;
        try {
            response = client.execute(op);
        } catch (IOException e) {
            throw new CommandLineException("Failed to load the list of deployments for overlay " + overlay, e);
        }

        final ModelNode result = response.get(Util.RESULT);
        if(!result.isDefined()) {
            final String descr = Util.getFailureDescription(response);
            if(descr != null && descr.contains("WFLYCTL0216")) {
                // resource doesn't exist
                return Collections.emptyList();
            }
            throw new CommandLineException("Failed to load the list of deployments for overlay " + overlay + ": " + response);
        }
        final List<String> contentList = new ArrayList<String>();
        for(ModelNode node : result.asList()) {
            contentList.add(node.asString());
        }
        return contentList;
    }

    protected ModelNode loadLinkResources(final ModelControllerClient client, String overlay, String serverGroup) throws CommandLineException {
        final ModelNode op = new ModelNode();
        final ModelNode addr = op.get(Util.ADDRESS);
        if(serverGroup != null) {
            addr.add(Util.SERVER_GROUP, serverGroup);
        }
        addr.add(Util.DEPLOYMENT_OVERLAY, overlay);
        op.get(Util.OPERATION).set(Util.READ_CHILDREN_RESOURCES);
        op.get(Util.CHILD_TYPE).set(Util.DEPLOYMENT);
        final ModelNode response;
        try {
            response = client.execute(op);
        } catch (IOException e) {
            throw new CommandLineException("Failed to load the list of deployments for overlay " + overlay, e);
        }

        final ModelNode result = response.get(Util.RESULT);
        if(!result.isDefined()) {
            final String descr = Util.getFailureDescription(response);
            if(descr != null && (descr.contains("WFLYCTL0216") || descr.contains("WFLYCTL0202"))) {
                // resource doesn't exist
                return null;
            }
            throw new CommandLineException("Failed to load the list of deployments for overlay " + overlay + ": " + response);
        }
        return result;
    }

    protected String[] getLinks(ArgumentWithValue linksArg, final ParsedCommandLine args) throws CommandFormatException {
        final String deploymentsStr = linksArg.getValue(args);
        final String[] deployments;
        if(deploymentsStr == null) {
            deployments = null;
        } else {
            deployments = deploymentsStr.split(",+");
            if(deployments.length == 0) {
                throw new CommandFormatException(linksArg.getFullName() + " is missing value.");
            }
        }
        return deployments;
    }

    protected List<String> getServerGroupsToLink(CommandContext ctx) throws CommandFormatException {
        final List<String> sg;
        final String sgStr = serverGroups.getValue(ctx.getParsedCommandLine());
        if(allServerGroups.isPresent(ctx.getParsedCommandLine())) {
            if(sgStr != null) {
                throw new CommandFormatException("Only one of " + allServerGroups.getFullName() + " or " + serverGroups.getFullName() + " can be specified at a time.");
            }
            sg = Util.getServerGroups(ctx.getModelControllerClient());
            if(sg.isEmpty()) {
                throw new CommandFormatException("No server group is available.");
            }
        } else {
            if(sgStr == null) {
                throw new CommandFormatException(serverGroups.getFullName() + " or " + allServerGroups.getFullName() + " must be specified.");
            }
            sg = Arrays.asList(sgStr.split(",+"));
            if(sg.isEmpty()) {
                throw new CommandFormatException(serverGroups.getFullName() + " is missing value.");
            }
        }
        return sg;
    }

    protected void addAddRedeployLinksSteps(CommandContext ctx, ModelNode steps,
            String overlay, String serverGroup, String[] links, boolean regexp)
                    throws CommandLineException {
        boolean warn = false;
        for(String link : links) {
            final ModelNode op = new ModelNode();
            final ModelNode address = op.get(Util.ADDRESS);
            if(serverGroup != null) {
                address.add(Util.SERVER_GROUP, serverGroup);
            }
            address.add(Util.DEPLOYMENT_OVERLAY, overlay);
            address.add(Util.DEPLOYMENT, link);
            op.get(Util.OPERATION).set(Util.ADD);
            steps.add(op);

            if (redeployAffected.isPresent(ctx.getParsedCommandLine())) {
                addRedeployStep(overlay, link, serverGroup, steps);
            } else {
                warn = true;
            }
        }
        if (warn) {
            String warningMsg = serverGroup == null
                    ? WARN_MSG + Arrays.toString(links)
                    : WARN_MSG + Arrays.toString(links) + " in server group " + serverGroup;
            ctx.printLine(warningMsg);
        }
    }

    protected void assertNotPresent(ArgumentWithoutValue arg, ParsedCommandLine args) throws CommandFormatException {
        if(arg.isPresent(args)) {
            throw new CommandFormatException(arg.getFullName() + " is not allowed with action '" + action.getValue(args) + "'");
        }
    }

    protected List<String> filterLinks(final ModelNode linkResources) {
        if(linkResources != null && !linkResources.keys().isEmpty()) {
            final List<Property> links = linkResources.asPropertyList();
            final List<String> linkNames = new ArrayList<String>(links.size());
            for (Property link : links) {
                linkNames.add(link.getName());
            }
            return linkNames;
        }
        return Collections.emptyList();
    }
}
