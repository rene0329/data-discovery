package org.example.service;

import org.example.entity.NodeManagement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DataTransferAddressResolverTest {
    private final DataTransferAddressResolver resolver = new DataTransferAddressResolver(
            "cluster-sh-1:172.28.241.199, cluster-sh-3:172.28.241.196,cluster-sz-2:172.28.225.70,malformed");

    @Test
    void sameSiteNodesWithPrivateAddressesUseTheSourcePrivateAddress() {
        assertEquals("172.28.241.196", resolver.sourceAddress(
                node("cluster-sh-3", "10.214.0.5"), node("cluster-sh-1", "10.214.0.9")));
    }

    @Test
    void crossSiteTransfersKeepTheInternalAddress() {
        assertEquals("10.214.0.5", resolver.sourceAddress(
                node("cluster-sh-3", "10.214.0.5"), node("cluster-sz-2", "10.214.0.15")));
        assertEquals("10.214.0.5", resolver.sourceAddress(
                node("cluster-sh-3", "10.214.0.5"), node("master-40", "10.15.16.40")));
    }

    @Test
    void consumerWithoutPrivateAddressKeepsTheInternalAddress() {
        assertEquals("10.214.0.5", resolver.sourceAddress(
                node("cluster-sh-3", "10.214.0.5"), node("cluster-sh-2", "10.214.0.11")));
    }

    @Test
    void sameNodeKeepsTheInternalAddress() {
        assertEquals("10.214.0.5", resolver.sourceAddress(
                node("cluster-sh-3", "10.214.0.5"), node("cluster-sh-3", "10.214.0.5")));
    }

    @Test
    void unconfiguredResolverAndMissingConsumerKeepTheInternalAddress() {
        DataTransferAddressResolver none = new DataTransferAddressResolver("");
        assertEquals("10.214.0.5", none.sourceAddress(
                node("cluster-sh-3", "10.214.0.5"), node("cluster-sh-1", "10.214.0.9")));
        assertEquals("10.214.0.5", resolver.sourceAddress(node("cluster-sh-3", "10.214.0.5"), null));
    }

    private NodeManagement node(String name, String internalIp) {
        return NodeManagement.builder().nodeName(name).internalIp(internalIp).build();
    }
}
