package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.NodeManagement;
import org.example.mapper.NodeManagementMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tracks the logical links occupied while a Job init container is transferring data. */
@Service
@Slf4j
public class ActiveDataTransferRegistry {
    private final NodeManagementMapper nodes;
    private final NetworkTopologyService topology;
    private final ConcurrentMap<String, ActiveTransfer> active = new ConcurrentHashMap<>();

    public ActiveDataTransferRegistry(NodeManagementMapper nodes, NetworkTopologyService topology) {
        this.nodes = nodes;
        this.topology = topology;
    }

    public void begin(String transferId, String sourceNode, String targetNode) {
        if (transferId == null || sourceNode == null || targetNode == null) return;
        Set<String> occupiedLinks = resolveOccupiedLinks(sourceNode, targetNode);
        active.put(transferId, new ActiveTransfer(occupiedLinks));
        log.info("登记业务传输 {}: {} → {}，占用逻辑链路 {}", transferId, sourceNode, targetNode, occupiedLinks);
    }

    public void complete(String transferId) {
        if (transferId != null && active.remove(transferId) != null) {
            log.info("业务传输 {} 已结束，释放探测避让标记", transferId);
        }
    }

    public boolean isLinkBusy(String firstNode, String secondNode) {
        String link = pairKey(firstNode, secondNode);
        if (link == null) return true;
        for (ActiveTransfer transfer : active.values()) {
            if (transfer.occupiedLinks.contains(link)) return true;
        }
        return false;
    }

    public int activeTransferCount() {
        return active.size();
    }

    private Set<String> resolveOccupiedLinks(String sourceName, String targetName) {
        Set<String> links = new HashSet<>();
        String direct = pairKey(sourceName, targetName);
        if (direct != null) links.add(direct);
        try {
            NodeManagement source = nodes.getNodeByName(sourceName);
            NodeManagement target = nodes.getNodeByName(targetName);
            if (source == null || target == null) return Collections.unmodifiableSet(links);
            NetworkTopologyService.NetworkPath path = topology.pathsFrom(source.getNodeId()).get(target.getNodeId());
            if (path == null) return Collections.unmodifiableSet(links);
            List<Integer> route = path.getNodeIds();
            for (int index = 1; index < route.size(); index++) {
                NodeManagement left = nodes.getNodeById(route.get(index - 1));
                NodeManagement right = nodes.getNodeById(route.get(index));
                String routeLink = pairKey(left == null ? null : left.getNodeName(),
                        right == null ? null : right.getNodeName());
                if (routeLink != null) links.add(routeLink);
            }
        } catch (RuntimeException ex) {
            log.warn("无法解析业务传输 {} → {} 的逻辑路径，仅避让直接链路: {}",
                    sourceName, targetName, ex.getMessage());
        }
        return Collections.unmodifiableSet(links);
    }

    private static String pairKey(String first, String second) {
        if (first == null || second == null || first.trim().isEmpty() || second.trim().isEmpty()) return null;
        String left = first.trim().toLowerCase(Locale.ROOT);
        String right = second.trim().toLowerCase(Locale.ROOT);
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    private static final class ActiveTransfer {
        private final Set<String> occupiedLinks;

        private ActiveTransfer(Set<String> occupiedLinks) {
            this.occupiedLinks = occupiedLinks;
        }
    }
}
