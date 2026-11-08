package org.example.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.entity.DatasetReplica;
import org.example.entity.DurableTaskJob;
import org.example.entity.NodeManagement;
import org.example.service.InPlacePlacementService.Placement;
import org.example.service.InPlacePlacementService.Tier;
import org.example.service.NetworkTopologyService.NetworkPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SchedulingCandidateLoggerTest {
    private final SchedulingCandidateLogger candidateLogger = new SchedulingCandidateLogger();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SchedulingCandidateLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private final DurableTaskJob row = new DurableTaskJob();
    private final NodeResourceLedger ledger = new NodeResourceLedger();
    private final JobResourceDemand demand = JobResourceDemand.of(.5, 1.0);
    private List<Placement> candidates;

    @BeforeEach
    void setup() {
        previousLevel = logger.getLevel(); logger.setLevel(Level.INFO);
        appender.start(); logger.addAppender(appender);
        row.setId(7L); row.setTaskId(42); row.setDatasetId(6L); row.setExecutionMode("IN_PLACE"); row.setAttempt(0);
        NodeManagement source = node(1);
        DatasetReplica replica = DatasetReplica.builder().replicaId(14L).nodeId(1).build();
        candidates = Arrays.asList(
                new Placement(replica, source, source, Tier.LOCAL, null, Collections.emptyList(), null),
                placement(replica, source, node(2), 15, 50),
                placement(replica, source, node(3), 20, 100),
                placement(replica, source, node(4), 30, 10));
        ledger.put("main/node-1", .25, 2, 0, 10);
        ledger.put("main/node-2", 1, 2, 0, 10);
        ledger.put("main/node-3", 1, 2, 0, 10);
    }

    @AfterEach
    void close() { logger.detachAppender(appender); logger.setLevel(previousLevel); appender.stop(); }

    @Test
    void infoLogsContainEveryCandidateAndExplainNonSelectedNodes() {
        candidateLogger.report(row, candidates, ledger, demand, candidates.get(1));
        List<String> lines = messages();
        assertEquals(5, lines.size());
        assertTrue(appender.list.stream().allMatch(e -> e.getLevel() == Level.INFO));
        assertTrue(lines.get(0).contains("candidateCount=4"));
        assertTrue(lines.get(1).contains("latencyMs=0.0"));
        assertTrue(lines.get(1).contains("CPU不足"));
        assertTrue(lines.get(2).contains("sourceNode=node-1 targetNode=node-2"));
        assertTrue(lines.get(2).contains("latencyMs=15.0 bandwidthMbps=50"));
        assertTrue(lines.get(2).contains("outcome=PREFERRED"));
        assertTrue(lines.get(3).contains("targetNode=node-3"));
        assertTrue(lines.get(3).contains("latencyMs=20.0 bandwidthMbps=100"));
        assertTrue(lines.get(3).contains("outcome=LOWER_PRIORITY"));
        assertTrue(lines.get(4).contains("outcome=RESOURCE_UNKNOWN"));
        assertTrue(lines.get(4).contains("cpuFree=null"));
    }

    @Test
    void unchangedPollingIsQuietButEligibilityAndMeasurementChangesAreLogged() {
        candidateLogger.report(row, candidates, ledger, demand, candidates.get(1));
        ledger.put("main/node-1", .2, 2, 0, 10);
        candidateLogger.report(row, candidates, ledger, demand, candidates.get(1));
        assertEquals(5, messages().size(), "numeric fluctuations must not flood a waiting job's logs");
        ledger.put("main/node-1", 1, 2, 0, 10);
        candidateLogger.report(row, candidates, ledger, demand, candidates.get(0));
        assertEquals(10, messages().size());
        candidates.set(2, placement(candidates.get(2).getReplica(), node(1), node(3), 25, 80));
        candidateLogger.report(row, candidates, ledger, demand, candidates.get(0));
        assertEquals(15, messages().size());
        candidateLogger.retainJobs(Collections.emptySet());
        candidateLogger.report(row, candidates, ledger, demand, candidates.get(0));
        assertEquals(20, messages().size(), "finished jobs must not leave suppression entries behind");
    }

    @Test
    void missingNetworkMeasurementsAreNeverReportedAsZeroLatency() {
        Placement fixed = new Placement(candidates.get(0).getReplica(), node(1), node(2),
                Tier.NEAREST, null, Collections.emptyList(), null);
        candidateLogger.report(row, Collections.singletonList(fixed), ledger, demand, fixed);
        assertTrue(messages().get(1).contains("latencyMs=未测量 bandwidthMbps=未测量"));
    }

    private List<String> messages() { return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList()); }
    private NodeManagement node(int id) { return NodeManagement.builder().nodeId(id).nodeName("node-" + id).cluster("main").build(); }
    private Placement placement(DatasetReplica replica, NodeManagement source, NodeManagement target, double latency, long bandwidth) {
        return new Placement(replica, source, target, Tier.NEAREST,
                new NetworkPath(Arrays.asList(1, target.getNodeId()), latency, bandwidth), Collections.emptyList(), null);
    }
}
