package vn.bully.server.support;

/** Small UDP multicast contract used only by server-node membership discovery. */
public record DiscoveryMessage(int version, String clusterId, String type,
                               int nodeId, int tcpPort, String bootId) {
}
