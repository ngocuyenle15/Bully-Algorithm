package vn.bully.worker.support;

public record NodeAddress(int id, String host, int port) {
    public static NodeAddress parse(String text) {
        String[] idAndAddress = text.trim().split("@", 2);
        String[] address = idAndAddress[1].split(":", 2);
        int port = Integer.parseInt(address[1]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        return new NodeAddress(Integer.parseInt(idAndAddress[0]), address[0], port);
    }
    @Override public String toString() { return id + "@" + host + ":" + port; }
}
