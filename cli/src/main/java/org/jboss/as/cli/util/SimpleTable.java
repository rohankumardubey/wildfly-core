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
package org.jboss.as.cli.util;

import static org.wildfly.common.Assert.checkNotNullParam;
import static org.wildfly.common.Assert.checkNotEmptyParam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.List;

/**
 *
 * @author Alexey Loubyansky
 */
public class SimpleTable {

    private final Object[] header;
    private final int[] columnLengths;
    private final List<String[]> lines = new ArrayList<String[]>();
    private final int terminalWidth;

    public SimpleTable(String[] header, int terminalWidth) {
        this.terminalWidth = terminalWidth;
        checkNotEmptyParam("header", header);
        this.header = new String[header.length];
        columnLengths = new int[header.length];
        for(int i = 0; i < header.length; ++i) {
            final String name = header[i];
            if(name == null) {
                throw new IllegalArgumentException("One of the headers is null: " + Arrays.asList(header));
            }
            this.header[i] = name;
            columnLengths[i] = name.length() + 1;
        }
    }

    public SimpleTable(int columnsTotal, int terminalWidth) {
        this.terminalWidth = terminalWidth;
        this.header = null;
        columnLengths = new int[columnsTotal];
    }

    public int columnsTotal() {
        return columnLengths.length;
    }

    public void addLine(String... line) {
        checkNotNullParam("line", line);
        if(line.length != columnLengths.length) {
            throw new IllegalArgumentException("Line length " + line.length + " doesn't match headers' length " + header.length);
        }

        final String[] values = new String[line.length];
        for(int i = 0; i < line.length; ++i) {
            String value = line[i];
            if(value == null) {
                value = "null";
            }
            values[i] = value;
            if (columnLengths[i] == 0) {
                // WFCORE-2812 and WFCORE-3540 the SimpleTable constructor allows to create object without headers. We assign the
                // smaller one to it.
                columnLengths[i] = (value.length() < terminalWidth) ? value.length() + 1 : terminalWidth;
            } else if (columnLengths[i] < value.length() + 1 && value.length() < terminalWidth) {
                columnLengths[i] = value.length() + 1;
            }
        }
        lines.add(values);
    }

    public int size() {
        return lines.size();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public String toString() {
        return toString(false);
    }

    public String toString(boolean order) {
        return append(new StringBuilder(), order).toString();
    }

    public StringBuilder append(StringBuilder buf, boolean order) {
        try (Formatter formatter = new Formatter(buf)) {
            final StringBuilder formatBuf = new StringBuilder();
            for(int length : columnLengths) {
                formatBuf.append("%-").append(length).append('s');
            }
            final String format = formatBuf.toString();
            if(header != null) {
                formatter.format(format, header);
                buf.append('\n');
            }

            if(order) {
                Collections.sort(lines, new Comparator<String[]>(){
                    @Override
                    public int compare(String[] o1, String[] o2) {
                        return o1[0].compareTo(o2[0]);
                    }});
            }

            int i = 0;
            if(i < lines.size()) {
                formatter.format(format, (Object[])lines.get(i));
            }
            while(++i < lines.size()) {
                buf.append('\n');
                formatter.format(format, (Object[])lines.get(i));
            }
        }
        return buf;
    }
}
