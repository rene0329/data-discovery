package org.example.factory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathRateLimitsTest {

    @Test
    void firstMatchingRuleWinsAndStarMatchesAnyNode() {
        PathRateLimits limits = PathRateLimits.parse(
                " cluster-hz-1->master-40=1M , *->master-215=2560k, *->master-40=1536k ");

        assertEquals("1M", limits.resolve("cluster-hz-1", "master-40"));
        assertEquals("1536k", limits.resolve("master-141", "master-40"));
        assertEquals("2560k", limits.resolve("cluster-hz-1", "MASTER-215"));
        assertNull(limits.resolve("cluster-sh-2", "cluster-sh-1"));
        assertEquals("cluster-hz-1->master-40=1M,*->master-215=2560k,*->master-40=1536k", limits.toString());
    }

    @Test
    void emptyConfigurationHasNoLimits() {
        assertNull(PathRateLimits.parse(null).resolve("a", "b"));
        assertNull(PathRateLimits.parse("  ,  ").resolve("a", "b"));
        assertEquals("(无)", PathRateLimits.parse("").toString());
    }

    @Test
    void malformedRulesAreRejected() {
        for (String rule : new String[]{"master-40=1M", "->master-40=1M", "*->=1M", "*->master-40=", "*->master-40=fast"}) {
            assertThrows(IllegalArgumentException.class, () -> PathRateLimits.parse(rule), rule);
        }
    }
}
