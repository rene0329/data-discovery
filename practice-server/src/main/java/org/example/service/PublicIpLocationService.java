package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.dto.PublicIpLocation;
import org.example.utils.PublicIpv4;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PublicIpLocationService {
    private final Ip2Region database;

    public PublicIpLocationService(
            @Value("${node-geolocation.database:classpath:geoip/ip2region_v4.xdb}") Resource resource) {
        Ip2Region loaded = null;
        try (InputStream input = resource.getInputStream()) {
            // The buffer-backed service is thread-safe; no network access on topology polling.
            loaded = Ip2Region.create(Config.custom().setCachePolicy(Config.BufferCache)
                    .setXdbInputStream(input).asV4(), null);
        } catch (Exception failure) {
            log.warn("IP location database unavailable: {}", failure.getMessage());
        }
        database = loaded;
    }

    public PublicIpLocation lookup(String externalIp) {
        if (externalIp == null || externalIp.trim().isEmpty()) {
            return result(null, null, "MISSING_IP");
        }
        String ip = PublicIpv4.normalize(externalIp);
        if (ip == null) return result(externalIp.trim(), null, "INVALID_IP");
        if (database == null) return result(ip, null, "UNAVAILABLE");
        try {
            String location = formatRegion(database.search(ip));
            return result(ip, location, location == null ? "NOT_FOUND" : "RESOLVED");
        } catch (Exception failure) {
            log.warn("IP location lookup failed for {}: {}", ip, failure.getMessage());
            return result(ip, null, "UNAVAILABLE");
        }
    }

    static String formatRegion(String region) {
        if (region == null) return null;
        // Pinned v4 data format: country|province|city|ISP|country-code.
        String location = Arrays.stream(region.split("\\|", -1)).limit(3)
                .map(String::trim).filter(part -> !part.isEmpty() && !"0".equals(part))
                .distinct().collect(Collectors.joining(" · "));
        return location.isEmpty() ? null : location;
    }

    private PublicIpLocation result(String ip, String location, String status) {
        return new PublicIpLocation(ip, location, status, "ip2region");
    }

    @PreDestroy
    public void close() throws Exception {
        if (database != null) database.close();
    }
}
