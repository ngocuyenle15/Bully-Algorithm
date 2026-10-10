package vn.bully.worker.testsupport;

import java.util.Map;

public record TcpMessage(MessageType type, String requestId,
                         String senderId, Map<String, Object> payload) {
}
