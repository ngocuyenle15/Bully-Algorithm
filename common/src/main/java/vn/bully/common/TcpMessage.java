package vn.bully.common;

import java.util.Map;

public record TcpMessage(MessageType type, String requestId,
                         String senderId, Map<String, Object> payload) {
}
