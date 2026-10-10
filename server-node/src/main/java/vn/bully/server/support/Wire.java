package vn.bully.server.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Wire {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LINE = 65536;
    private Wire() {}
    public static TcpMessage message(MessageType type, String requestId, String sender, Map<String, Object> payload) {
        return new TcpMessage(type, requestId, sender, payload);
    }
    public static TcpMessage call(NodeAddress node, TcpMessage message, int timeout) throws IOException {
        return call(node, message, timeout, null, 0);
    }
    public static TcpMessage call(NodeAddress node, TcpMessage message, int timeout, InetAddress localAddress, int localPort) throws IOException {
        try (var socket = new Socket()) {
            if (localAddress != null) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(localAddress, localPort));
            }
            socket.connect(new InetSocketAddress(node.host(), node.port()), timeout);
            socket.setSoTimeout(timeout);
            write(socket, message);
            return read(socket);
        }
    }
    public static void write(Socket socket, TcpMessage message) throws IOException {
        byte[] data = JSON.writeValueAsBytes(message);
        if (data.length > MAX_LINE) throw new IOException("TCP message too large");
        var out = socket.getOutputStream(); out.write(data); out.write('\n'); out.flush();
    }
    public static TcpMessage read(Socket socket) throws IOException {
        var input = socket.getInputStream();
        var bytes = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != '\n') {
            if (b == -1) throw new EOFException("Connection closed before message");
            if (bytes.size() >= MAX_LINE) throw new IOException("TCP message too large");
            bytes.write(b);
        }
        var message = JSON.readValue(bytes.toString(StandardCharsets.UTF_8), TcpMessage.class);
        if (message.type() == null || message.payload() == null) throw new IOException("Invalid TCP message");
        return message;
    }
    public static String string(TcpMessage m, String key) { return Objects.toString(m.payload().get(key), ""); }
    public static int integer(TcpMessage m, String key) { return Integer.parseInt(string(m, key)); }
}
