package vn.bully.server.support;

import java.util.Map;

public record TcpMessage(MessageType type, String requestId,
                         String senderId, Map<String, Object> payload) {
}
