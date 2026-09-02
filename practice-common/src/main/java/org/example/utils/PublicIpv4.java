package org.example.utils;

import org.apache.http.conn.util.InetAddressUtils;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Validates the plain IPv4 response without resolving arbitrary hostnames. */
public final class PublicIpv4 {
    private PublicIpv4() { }

    public static String normalize(String value) {
        if (value == null) return null;
        String ip = value.trim();
        if (ip.length() > 15 || !InetAddressUtils.isIPv4Address(ip)) return null;
        try {
            InetAddress address = InetAddress.getByName(ip);
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            int third = bytes[2] & 255;
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || first == 0 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)) return null;
            return ip;
        } catch (UnknownHostException invalid) {
            return null;
        }
    }
}
