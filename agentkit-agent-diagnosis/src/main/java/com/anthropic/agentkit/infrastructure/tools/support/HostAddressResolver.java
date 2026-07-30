package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;

/**
 * DNS resolution seam used to enforce final-address SSRF policy.
 *
 * @author alex
 */
@FunctionalInterface
public interface HostAddressResolver {

    Set<InetAddress> resolve(String host) throws IOException;

    static HostAddressResolver system() {
        return host -> Set.copyOf(Arrays.asList(InetAddress.getAllByName(host)));
    }
}
