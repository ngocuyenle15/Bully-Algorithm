package vn.bully.server.support;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.*;

public final class Config {
    private final Properties values = new Properties();
    public Config(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                try (var in = Files.newBufferedReader(Path.of(args[++i]), StandardCharsets.UTF_8)) { values.load(in); }
            }
        }
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option: " + args[i]);
            String key = args[i].substring(2);
            if (key.equals("headless")) values.setProperty(key, "true");
            else {
                if (++i >= args.length) throw new IllegalArgumentException("Missing value: " + key);
                if (!key.equals("config")) values.setProperty(key, args[i]);
            }
        }
    }
    public String get(String key, String fallback) { return values.getProperty(key, fallback); }
    public int number(String key, int fallback) {
        int value = Integer.parseInt(get(key, Integer.toString(fallback)));
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }
    public boolean headless() { return Boolean.parseBoolean(get("headless", "false")); }
    public boolean flag(String key, boolean fallback) {
        String value = get(key, Boolean.toString(fallback)).trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }
    public InetAddress bindAddress() {
        try {
            String value = get("bind-address", "0.0.0.0").trim();
            if (value.isEmpty()) throw new IllegalArgumentException("bind-address must not be empty");
            var address = InetAddress.getByName(value);
            if (!address.isAnyLocalAddress() && NetworkInterface.getByInetAddress(address) == null)
                throw new IllegalArgumentException("bind-address is not assigned to a local interface: " + value);
            return address;
        } catch (IOException e) { throw new IllegalArgumentException("Invalid bind-address", e); }
    }
    public int port(String key, int fallback, boolean allowZero) {
        int value = Integer.parseInt(get(key, Integer.toString(fallback)));
        if (value < (allowZero ? 0 : 1) || value > 65535)
            throw new IllegalArgumentException(key + " must be " + (allowZero ? "0" : "1") + "–65535");
        return value;
    }
    public List<NodeAddress> nodes() {
        String configured = get("servers", "").trim();
        if (configured.isEmpty()) return List.of();
        var nodes = Arrays.stream(configured.split(","))
            .map(NodeAddress::parse).toList();
        if (nodes.stream().anyMatch(node -> node.id() <= 0))
            throw new IllegalArgumentException("Server ID must be positive");
        if (nodes.stream().map(NodeAddress::id).distinct().count() != nodes.size())
            throw new IllegalArgumentException("Duplicate server ID");
        return nodes;
    }
    public String clusterId() {
        String value = get("cluster-id", "bully-demo").trim();
        if (value.isEmpty() || value.length() > 100) throw new IllegalArgumentException("Invalid cluster-id");
        return value;
    }
    public InetAddress discoveryGroup() {
        try {
            var group = InetAddress.getByName(get("discovery-group", "239.255.42.99").trim());
            if (!group.isMulticastAddress()) throw new IllegalArgumentException("discovery-group must be multicast");
            return group;
        } catch (IOException e) { throw new IllegalArgumentException("Invalid discovery-group", e); }
    }
    public NodeAddress worker() { return NodeAddress.parse("0@" + get("worker", "127.0.0.1:9100")); }
}
