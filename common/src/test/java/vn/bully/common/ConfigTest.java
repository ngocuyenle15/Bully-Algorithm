package vn.bully.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @TempDir Path directory;
    @Test void unicodePropertiesAreLoadedAndCommandLineWins() throws Exception {
        Path file = directory.resolve("client.properties");
        Files.writeString(file, "id=client-1\nusername=Nguyễn Văn An\nworker=192.168.1.10:9100\n");
        var config = new Config(new String[]{"--id", "client-2", "--config", file.toString(), "--headless"});
        assertEquals("Nguyễn Văn An", config.get("username", ""));
        assertEquals("client-2", config.get("id", ""));
        assertEquals("192.168.1.10", config.worker().host()); assertTrue(config.headless());
    }
    @Test void invalidNodeConfigurationIsRejected() throws Exception {
        var config = new Config(new String[]{"--servers", "1@127.0.0.1:9001,1@127.0.0.1:9002"});
        assertThrows(IllegalArgumentException.class, config::nodes);
        assertThrows(IllegalArgumentException.class, () -> NodeAddress.parse("1@127.0.0.1:70000"));
        assertThrows(IllegalArgumentException.class, () -> new Config(new String[]{"--id"}));
    }
}
