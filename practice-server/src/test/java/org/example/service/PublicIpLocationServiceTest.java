package org.example.service;

import org.example.dto.PublicIpLocation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PublicIpLocationServiceTest {
    @Test
    void looksUpRealPublicAddressesUsingBundledDatabaseAndSupportsConcurrency() throws Exception {
        PublicIpLocationService service = new PublicIpLocationService(new ClassPathResource("geoip/ip2region_v4.xdb"));
        try {
            for (String ip : Arrays.asList("47.116.9.113", "121.43.57.204", "182.92.121.10",
                    "42.228.13.134", "171.8.254.45", "42.228.13.137")) {
                PublicIpLocation location = service.lookup(ip);
                assertEquals("RESOLVED", location.getStatus(), ip);
                assertEquals(ip, location.getIp());
                assertNotNull(location.getDisplayName());
                System.out.println(ip + " -> " + location.getDisplayName());
            }
            String shanghai = service.lookup("47.116.9.113").getDisplayName();
            assertTrue(shanghai.contains("上海"));
            assertEquals(service.lookup("47.116.9.113"), service.lookup(" 47.116.9.113 "));
            IntStream.range(0, 50).parallel().forEach(i ->
                    assertEquals(shanghai, service.lookup("47.116.9.113").getDisplayName()));
        } finally {
            service.close();
        }
    }

    @Test
    void missingOrInvalidDatabaseDoesNotBreakTopologyAndInvalidIpsAreNotLookedUp() throws Exception {
        PublicIpLocationService service = new PublicIpLocationService(new ByteArrayResource(new byte[0]));
        try {
            assertEquals("UNAVAILABLE", service.lookup("47.116.9.113").getStatus());
            assertEquals("MISSING_IP", service.lookup(null).getStatus());
            assertEquals("MISSING_IP", service.lookup(" ").getStatus());
            for (String invalid : Arrays.asList("10.213.0.5", "127.0.0.1", "100.64.0.1",
                    "192.0.2.1", "example.com", "47.116.9.113:80", "::1")) {
                assertEquals("INVALID_IP", service.lookup(invalid).getStatus());
                assertNull(service.lookup(invalid).getDisplayName());
            }
        } finally {
            service.close();
        }
    }

    @Test
    void formatsOnlyGeographyWithoutIspCountryCodeOrDuplicateCity() {
        assertEquals("中国 · 上海市", PublicIpLocationService.formatRegion("中国|上海市|上海市|阿里云|CN"));
        assertEquals("中国 · 浙江省 · 杭州市", PublicIpLocationService.formatRegion("中国|浙江省|杭州市|阿里云|CN"));
        assertEquals("United States", PublicIpLocationService.formatRegion("United States|0|0|Google|US"));
        assertNull(PublicIpLocationService.formatRegion("0|0|0|ISP|XX"));
        assertNull(PublicIpLocationService.formatRegion(""));
        assertNull(PublicIpLocationService.formatRegion(null));
    }
}
