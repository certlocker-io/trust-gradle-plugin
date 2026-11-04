package io.certlocker.trust.gradle;

import com.sun.net.httpserver.HttpServer;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GatewayCompatibilityTest {
    @Test void legacyAndAccountModesReachTheExpectedGateway() throws Exception {
        for (boolean legacy : new boolean[]{true, false}) {
            List<String> requests = new ArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestURI().getPath());
                byte[] bytes = "synthetic-value".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var project = ProjectBuilder.builder().build();
                project.getPluginManager().apply(TrustPlugin.class);
                var extension = project.getExtensions().getByType(TrustExtension.class);
                extension.getUrl().set("http://127.0.0.1:" + server.getAddress().getPort());
                extension.getApiToken().set("synthetic-token");
                extension.getLegacyApi().set(legacy);
                if (!legacy) extension.getAccountId().set("acct");
                try (var service = extension.getService()) {
                    assertEquals("synthetic-value", service.fetchSecret("fixture"));
                }
                assertEquals(List.of("/rest/api/v1/secret/" + (legacy ? "" : "acct/") + "fetch/fixture"), requests);
            } finally { server.stop(0); }
        }
    }
}
