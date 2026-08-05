package com.example.nodecontrol.service;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class HostAddressResolver {

    public Set<String> resolve(String host) {
        if (host == null || host.isBlank()) {
            return Set.of();
        }
        try {
            return Arrays.stream(InetAddress.getAllByName(host))
                    .map(InetAddress::getHostAddress)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (UnknownHostException ignored) {
            return Set.of();
        }
    }
}
