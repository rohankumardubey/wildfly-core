/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
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
package org.jboss.as.cli.handlers.ifelse;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineException;
import org.jboss.dmr.ModelNode;

/**
*
* @author Alexey Loubyansky
*/
class AndOperation extends BaseOperation {

    static final String SYMBOL = "&&";

    AndOperation() {
        super(SYMBOL, 4);
    }

    @Override
    public Object resolveValue(CommandContext ctx, ModelNode response) throws CommandLineException {
        for(Operand operand : getOperands()) {
            final Object value = operand.resolveValue(ctx, response);
            if(!(value instanceof Boolean)) {
                throw new CommandFormatException("Expected boolean value from " + operand + " but received " + value);
            }
            if(!((Boolean)value)) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    @Override
    public boolean allowsMoreArguments() {
        return true;
    }
}