package com.octopyder.payroll.attendance;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

public final class CidrMatcher {

    private CidrMatcher() {}

    public static boolean matches(String clientIp, java.util.List<String> cidrList) {
        if (clientIp == null || clientIp.isBlank() || cidrList == null || cidrList.isEmpty()) {
            return false;
        }
        for (String cidr : cidrList) {
            if (cidr != null && !cidr.isBlank() && matchesSingle(clientIp.trim(), cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches(String clientIp, String cidrList) {
        if (clientIp == null || clientIp.isBlank() || cidrList == null || cidrList.isBlank()) {
            return false;
        }

        String[] cidrs = cidrList.split(",");
        for (String cidr : cidrs) {
            String trimmed = cidr.trim();
            if (!trimmed.isEmpty() && matchesSingle(clientIp.trim(), trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSingle(String ipStr, String cidrStr) {
        try {
            InetAddress clientAddr = InetAddress.getByName(ipStr);
            byte[] clientBytes = clientAddr.getAddress();

            if (!cidrStr.contains("/")) {
                InetAddress targetAddr = InetAddress.getByName(cidrStr);
                return clientAddr.equals(targetAddr);
            }

            String[] parts = cidrStr.split("/");
            if (parts.length != 2) {
                return false;
            }

            InetAddress networkAddr = InetAddress.getByName(parts[0].trim());
            byte[] networkBytes = networkAddr.getAddress();

            // Must be same IP version (IPv4 = 4 bytes, IPv6 = 16 bytes)
            if (clientBytes.length != networkBytes.length) {
                return false;
            }

            int prefixLength = Integer.parseInt(parts[1].trim());
            int totalBits = clientBytes.length * 8;
            if (prefixLength < 0 || prefixLength > totalBits) {
                return false;
            }

            BigInteger clientBig = new BigInteger(1, clientBytes);
            BigInteger networkBig = new BigInteger(1, networkBytes);

            int shift = totalBits - prefixLength;
            return clientBig.shiftRight(shift).equals(networkBig.shiftRight(shift));
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
