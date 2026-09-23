package org.example.service;

import org.example.entity.NodeManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Which address of a source node a consumer node should pull data from.
 *
 * Edge nodes register their SSH-TUN address as internal_ip, and every tunnel ends on a hub
 * node, so two nodes of the same site would exchange data through the hub and back. Nodes
 * listed in app.network-topology.lan-addresses also share a private (VPC) network with the
 * other listed nodes of their site: between two such nodes the source's private address is
 * used, everywhere else internal_ip.
 */
@Component
public class DataTransferAddressResolver {
    private static final Logger log = LoggerFactory.getLogger(DataTransferAddressResolver.class);

    private final Map<String, String> lanAddresses;

    public DataTransferAddressResolver(@Value("${app.network-topology.lan-addresses:}") String lanAddresses) {
        this.lanAddresses = parseLanAddresses(lanAddresses);
        log.info("同站点内网传输地址: {}", this.lanAddresses.isEmpty() ? "(未配置)" : this.lanAddresses);
    }

    /** "node:ip,node:ip" — a node's private address inside its site. */
    static Map<String, String> parseLanAddresses(String value) {
        Map<String, String> addresses = new HashMap<>();
        if (value == null) return addresses;
        for (String entry : value.split(",")) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
                addresses.put(parts[0].trim(), parts[1].trim());
            }
        }
        return Collections.unmodifiableMap(addresses);
    }

    public String sourceAddress(NodeManagement source, NodeManagement consumer) {
        String internalIp = source == null ? null : source.getInternalIp();
        if (source == null || consumer == null || source.getNodeName() == null || consumer.getNodeName() == null
                || source.getNodeName().trim().equals(consumer.getNodeName().trim())) {
            return internalIp;
        }
        String sourceLan = lanAddresses.get(source.getNodeName().trim());
        if (sourceLan == null || !lanAddresses.containsKey(consumer.getNodeName().trim())) return internalIp;
        boolean sameSite = NetworkTopologyService.siteOf(source.getNodeName())
                .equals(NetworkTopologyService.siteOf(consumer.getNodeName()));
        return sameSite ? sourceLan : internalIp;
    }
}
