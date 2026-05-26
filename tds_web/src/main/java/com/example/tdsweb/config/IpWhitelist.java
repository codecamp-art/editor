package com.example.tdsweb.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

final class IpWhitelist {
    private final List<IpRange> ranges;

    private IpWhitelist(List<IpRange> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    static IpWhitelist from(List<String> rawRanges) {
        List<IpRange> parsedRanges = new ArrayList<>();
        if (rawRanges == null) {
            return new IpWhitelist(parsedRanges);
        }
        for (String rawRange : rawRanges) {
            parsedRanges.add(IpRange.parse(rawRange));
        }
        return new IpWhitelist(parsedRanges);
    }

    boolean contains(String rawAddress) {
        InetAddress address;
        try {
            address = InetAddress.getByName(rawAddress);
        } catch (UnknownHostException ex) {
            return false;
        }
        byte[] candidate = address.getAddress();
        return ranges.stream().anyMatch(range -> range.matches(candidate));
    }

    private record IpRange(byte[] network, int prefixLength) {
        private static IpRange parse(String rawRange) {
            String normalized = rawRange == null ? "" : rawRange.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("IP whitelist entries must not be blank");
            }
            String[] parts = normalized.split("/", -1);
            if (parts.length > 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException("Invalid IP whitelist entry: " + normalized);
            }

            InetAddress address = parseAddress(parts[0], normalized);
            int maxPrefixLength = address.getAddress().length * Byte.SIZE;
            int prefix = maxPrefixLength;
            if (parts.length == 2) {
                prefix = parsePrefix(parts[1], normalized, maxPrefixLength);
            }
            return new IpRange(address.getAddress(), prefix);
        }

        private static InetAddress parseAddress(String rawAddress, String rawRange) {
            try {
                return InetAddress.getByName(rawAddress.trim());
            } catch (UnknownHostException ex) {
                throw new IllegalArgumentException("Invalid IP whitelist entry: " + rawRange, ex);
            }
        }

        private static int parsePrefix(String rawPrefix, String rawRange, int maxPrefixLength) {
            try {
                int prefix = Integer.parseInt(rawPrefix.trim());
                if (prefix < 0 || prefix > maxPrefixLength) {
                    throw new IllegalArgumentException("Invalid IP whitelist prefix length: " + rawRange);
                }
                return prefix;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid IP whitelist prefix length: " + rawRange, ex);
            }
        }

        private boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }

            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }

            if (remainingBits == 0) {
                return true;
            }

            int mask = (0xff << (Byte.SIZE - remainingBits)) & 0xff;
            return (Byte.toUnsignedInt(candidate[fullBytes]) & mask)
                == (Byte.toUnsignedInt(network[fullBytes]) & mask);
        }
    }
}
