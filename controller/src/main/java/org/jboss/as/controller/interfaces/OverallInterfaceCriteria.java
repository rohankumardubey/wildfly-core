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

package org.jboss.as.controller.interfaces;


import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jboss.as.controller.logging.ControllerLogger;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * Overall interface criteria. Encapsulates a set of individual criteria and selects interfaces and addresses
 * that meet them all.
 */
public final class OverallInterfaceCriteria implements InterfaceCriteria {
    // java.net properties
    static final String PREFER_IPV4_STACK = "java.net.preferIPv4Stack";
    static final String PREFER_IPV6_ADDRESSES = "java.net.preferIPv6Addresses";

    private static final long serialVersionUID = -5417786897309925997L;

    private final String interfaceName;
    private final Set<InterfaceCriteria> interfaceCriteria;

    public OverallInterfaceCriteria(final String interfaceName, Set<InterfaceCriteria> criteria) {
        this.interfaceName = interfaceName;
        this.interfaceCriteria = criteria;
    }

    @Override
    public Map<NetworkInterface, Set<InetAddress>> getAcceptableAddresses(Map<NetworkInterface, Set<InetAddress>> candidates) throws SocketException {

        Map<NetworkInterface, Set<InetAddress>> result = AbstractInterfaceCriteria.cloneCandidates(candidates);
        Set<InterfaceCriteria> sorted = new TreeSet<>(interfaceCriteria);
        for (InterfaceCriteria criteria : sorted) {
            result = criteria.getAcceptableAddresses(result);
            if (result.size() == 0) {
                break;
            }
        }

        if (result.size() > 0) {
            if (hasMultipleMatches(result)) {
                // Multiple options matched the criteria. Eliminate the same address showing up in both
                // a subinterface (an alias) and in the parent
                result = pruneAliasDuplicates(result);
            }
            if (hasMultipleMatches(result)) {
                // Multiple options matched the criteria. Try and narrow the selection based on
                // preferences indirectly expressed via -Djava.net.preferIPv4Stack and -Djava.net.preferIPv6Addresses
                result = pruneIPTypes(result);
            }
            if (hasMultipleMatches(result)) {
                // Multiple options matched the criteria; Pick one
                Map<NetworkInterface, Set<InetAddress>> selected = selectInterfaceAndAddress(result);
                // Warn user their criteria was insufficiently exact
                if (interfaceName != null) { // will be null if the resolution is being performed for the "resolved-address"
                                             // user query operation in which case we don't want to log a WARN
                    Map.Entry<NetworkInterface, Set<InetAddress>> entry = selected.entrySet().iterator().next();
                    warnMultipleValidInterfaces(interfaceName, result, entry.getKey(), entry.getValue().iterator().next());
                }
                result = selected;
            }
        }
        return result;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder("OverallInterfaceCriteria(");
        for (InterfaceCriteria criteria : interfaceCriteria) {
            sb.append(criteria.toString());
            sb.append(",");
        }
        sb.setLength(sb.length() - 1);
        sb.append(")");
        return sb.toString();
    }

    @Override
    public int compareTo(InterfaceCriteria o) {
        if (this.equals(o)) {
            return 0;
        }
        return 1;
    }

    private Map<NetworkInterface, Set<InetAddress>> pruneIPTypes(Map<NetworkInterface, Set<InetAddress>> candidates) {

        Boolean preferIPv4Stack = getBoolean(PREFER_IPV4_STACK);
        Boolean preferIPv6Stack = getBoolean(PREFER_IPV6_ADDRESSES);

        final Map<NetworkInterface, Set<InetAddress>> result = new HashMap<NetworkInterface, Set<InetAddress>>();

        for (Map.Entry<NetworkInterface, Set<InetAddress>> entry : candidates.entrySet()) {
            Set<InetAddress> good = null;
            for (InetAddress address : entry.getValue()) {
                if ((preferIPv4Stack && address instanceof Inet4Address)
                        || (!preferIPv4Stack && !preferIPv6Stack && address instanceof Inet4Address)
                        || (!preferIPv4Stack && !preferIPv6Stack && address instanceof Inet6Address && InetAddress.getLoopbackAddress() instanceof Inet6Address)
                        || (preferIPv6Stack && address instanceof Inet4Address && InetAddress.getLoopbackAddress() instanceof Inet4Address)
                        || (preferIPv6Stack && address instanceof Inet6Address && InetAddress.getLoopbackAddress() instanceof Inet6Address)) {
                    if (good == null) {
                        good = new HashSet<InetAddress>();
                        result.put(entry.getKey(), good);
                    }
                    good.add(address);
                }
            }
        }
        return result.size() == 0 ? candidates : result;
    }

    static Map<NetworkInterface, Set<InetAddress>> pruneAliasDuplicates(Map<NetworkInterface, Set<InetAddress>> result) {
        final Map<NetworkInterface, Set<InetAddress>> pruned = new HashMap<NetworkInterface, Set<InetAddress>>();
        for (Map.Entry<NetworkInterface, Set<InetAddress>> entry : result.entrySet()) {
            NetworkInterface ni = entry.getKey();
            if (ni.getParent() != null) {
                pruned.put(ni, entry.getValue());
            } else {
                Set<InetAddress> retained = new HashSet<InetAddress>(entry.getValue());
                Enumeration<NetworkInterface> subInterfaces = ni.getSubInterfaces();
                while (subInterfaces.hasMoreElements()) {
                    NetworkInterface sub = subInterfaces.nextElement();
                    Set<InetAddress> subAddresses = result.get(sub);
                    if (subAddresses != null) {
                        retained.removeAll(subAddresses);
                    }
                }
                if (!retained.isEmpty()) {
                    pruned.put(ni, retained);
                }
            }
        }
        return pruned;
    }

    private static Boolean getBoolean(final String property) {
        //default value for ipv4 and ipv6 is false
        final String value = WildFlySecurityManager.getPropertyPrivileged(property, null);
        return value == null ? false : value.equalsIgnoreCase("true");
    }

    private static Map<NetworkInterface, Set<InetAddress>> selectInterfaceAndAddress(Map<NetworkInterface, Set<InetAddress>> acceptable) throws SocketException {

        // Give preference to NetworkInterfaces that are 1) up, 2) not loopback 3) not point-to-point.
        // If any of these criteria eliminate all interfaces, discard it.
        if (acceptable.size() > 1) {
            Map<NetworkInterface, Set<InetAddress>> preferred = new HashMap<NetworkInterface, Set<InetAddress>>();
            for (NetworkInterface ni : acceptable.keySet()) {
                if (ni.isUp()) {
                    preferred.put(ni, acceptable.get(ni));
                }
            }
            if (preferred.size() > 0) {
                acceptable = preferred;
            } // else this preference eliminates all interfaces, so ignore it
        }
        if (acceptable.size() > 1) {
            Map<NetworkInterface, Set<InetAddress>> preferred = new HashMap<NetworkInterface, Set<InetAddress>>();
            for (NetworkInterface ni : acceptable.keySet()) {
                if (!ni.isLoopback()) {
                    preferred.put(ni, acceptable.get(ni));
                }
            }
            if (preferred.size() > 0) {
                acceptable = preferred;
            }  // else this preference eliminates all interfaces, so ignore it
        }
        if (acceptable.size() > 1) {
            Map<NetworkInterface, Set<InetAddress>> preferred = new HashMap<NetworkInterface, Set<InetAddress>>();
            for (NetworkInterface ni : acceptable.keySet()) {
                if (!ni.isPointToPoint()) {
                    preferred.put(ni, acceptable.get(ni));
                }
            }
            if (preferred.size() > 0) {
                acceptable = preferred;
            }  // else this preference eliminates all interfaces, so ignore it
        }


        if (hasMultipleMatches(acceptable)) {

            // Give preference to non-link-local addresses
            Map<NetworkInterface, Set<InetAddress>> preferred = new HashMap<NetworkInterface, Set<InetAddress>>();
            for (Map.Entry<NetworkInterface, Set<InetAddress>> entry : acceptable.entrySet()) {
                Set<InetAddress> acceptableAddresses = entry.getValue();
                if (acceptableAddresses.size() > 1) {
                    Set<InetAddress> preferredAddresses = null;
                    for (InetAddress addr : acceptableAddresses) {
                        if (!addr.isLinkLocalAddress()) {
                            if (preferredAddresses == null) {
                                preferredAddresses = new HashSet<InetAddress>();
                                preferred.put(entry.getKey(), preferredAddresses);
                            }
                            preferredAddresses.add(addr);
                        }
                    }
                } else {
                    acceptable.put(entry.getKey(), acceptableAddresses);
                }
            }

            if (preferred.size() > 0) {
                acceptable = preferred;
            } // else this preference eliminates all interfaces, so ignore it

        }

        Map.Entry<NetworkInterface, Set<InetAddress>> entry = acceptable.entrySet().iterator().next();
        return Collections.singletonMap(entry.getKey(), Collections.singleton(entry.getValue().iterator().next()));
    }

    private static boolean hasMultipleMatches(Map<NetworkInterface, Set<InetAddress>> map) {
        return map.size() > 1 || (map.size() == 1 && map.values().iterator().next().size() > 1);
    }

    private static void warnMultipleValidInterfaces(String interfaceName, Map<NetworkInterface, Set<InetAddress>> acceptable,
                                                    NetworkInterface selectedInterface, InetAddress selectedAddress) {
        Set<String> nis = new HashSet<String>();
        Set<InetAddress> addresses = new HashSet<InetAddress>();
        for (Map.Entry<NetworkInterface, Set<InetAddress>> entry : acceptable.entrySet()) {
            nis.add(entry.getKey().getName());
            addresses.addAll(entry.getValue());
        }
        ControllerLogger.ROOT_LOGGER.multipleMatchingAddresses(interfaceName, addresses, nis, selectedAddress, selectedInterface.getName());
    }
}
