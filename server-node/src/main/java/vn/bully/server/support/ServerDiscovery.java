package vn.bully.server.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Periodically announces this server and reports peers found on the same multicast group. */
public final class ServerDiscovery implements AutoCloseable {
    public record Hello(int nodeId, int tcpPort, String bootId, InetAddress sourceAddress) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PACKET = 2048;
    private final String clusterId, bootId = UUID.randomUUID().toString();
    private final int nodeId, tcpPort, discoveryPort;
    private final InetAddress group;
    private final NetworkInterface networkInterface;
    private final MulticastSocket socket;
    private final Consumer<Hello> listener;
    private final EventLog log;
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService receiver = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    public ServerDiscovery(String clusterId, int nodeId, int tcpPort, InetAddress group,
                           int discoveryPort, int intervalMs, InetAddress bindAddress,
                           Consumer<Hello> listener, EventLog log) throws IOException {
        if (!group.isMulticastAddress()) throw new IllegalArgumentException("discovery-group must be multicast");
        this.clusterId = clusterId;
        this.nodeId = nodeId;
        this.tcpPort = tcpPort;
        this.group = group;
        this.discoveryPort = discoveryPort;
        this.listener = listener;
        this.log = log;
        networkInterface = selectInterface(bindAddress, group);
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(discoveryPort));
        socket.setTimeToLive(1);
        socket.setNetworkInterface(networkInterface);
        socket.joinGroup(new InetSocketAddress(group, discoveryPort), networkInterface);
        receiver.submit(this::receiveLoop);
        sender.scheduleWithFixedDelay(this::announce, 0, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Discovery started group=" + group.getHostAddress() + ":" + discoveryPort
            + " interface=" + networkInterface.getDisplayName() + " cluster=" + clusterId);
    }

    private static NetworkInterface selectInterface(InetAddress bindAddress, InetAddress group) throws SocketException {
        if (!bindAddress.isAnyLocalAddress()) {
            var selected = NetworkInterface.getByInetAddress(bindAddress);
            if (selected == null || !selected.isUp())
                throw new IllegalArgumentException("No active discovery interface for " + bindAddress.getHostAddress());
            if (!compatible(selected, group))
                throw new IllegalArgumentException("Discovery interface address family does not match multicast group");
            return selected;
        }
        var interfaces = NetworkInterface.networkInterfaces()
            .filter(item -> usable(item) && compatible(item, group))
            .sorted(Comparator.comparing((NetworkInterface item) -> {
                try { return item.isLoopback(); } catch (SocketException e) { return true; }
            }))
            .toList();
        if (interfaces.isEmpty()) throw new IllegalArgumentException("No multicast-capable network interface");
        return interfaces.getFirst();
    }

    private static boolean usable(NetworkInterface item) {
        try { return item.isUp() && item.supportsMulticast(); }
        catch (SocketException e) { return false; }
    }

    private static boolean compatible(NetworkInterface item, InetAddress group) {
        boolean ipv4 = group instanceof Inet4Address;
        return item.inetAddresses().anyMatch(address -> ipv4 == (address instanceof Inet4Address));
    }

    private void announce() {
        if (!running) return;
        try {
            var message = new DiscoveryMessage(1, clusterId, "SERVER_HELLO", nodeId, tcpPort, bootId);
            byte[] data = JSON.writeValueAsBytes(message);
            socket.send(new DatagramPacket(data, data.length, group, discoveryPort));
        } catch (Exception e) {
            if (running) log.info("Discovery announce failed: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] data = new byte[MAX_PACKET];
        while (running) {
            try {
                var packet = new DatagramPacket(data, data.length);
                socket.receive(packet);
                if (packet.getLength() >= MAX_PACKET) continue;
                var message = JSON.readValue(packet.getData(), packet.getOffset(), packet.getLength(), DiscoveryMessage.class);
                if (message.version() != 1 || !clusterId.equals(message.clusterId())
                    || !"SERVER_HELLO".equals(message.type()) || message.nodeId() <= 0
                    || message.tcpPort() < 1 || message.tcpPort() > 65535 || message.bootId() == null)
                    continue;
                if (message.nodeId() == nodeId && bootId.equals(message.bootId())) continue;
                listener.accept(new Hello(message.nodeId(), message.tcpPort(), message.bootId(), packet.getAddress()));
            } catch (SocketException e) {
                if (running) log.info("Discovery socket failed: " + e.getMessage());
            } catch (Exception e) {
                if (running) log.info("Invalid discovery packet: " + e.getMessage());
            }
        }
    }

    @Override public void close() {
        running = false;
        try { socket.leaveGroup(new InetSocketAddress(group, discoveryPort), networkInterface); }
        catch (IOException ignored) { }
        socket.close();
        sender.shutdownNow();
        receiver.shutdownNow();
    }
}
