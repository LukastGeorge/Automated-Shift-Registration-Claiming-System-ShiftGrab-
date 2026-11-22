package com.shiftgrab;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mailpit (the same SMTP catcher as infra/docker) with a tiny client for its HTTP API, for e-mail tests. */
public class MailpitContainer extends GenericContainer<MailpitContainer> {

    public static final int SMTP_PORT = 1025;
    private static final int API_PORT = 8025;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    public record Mail(String to, String subject, String text) {
    }

    public MailpitContainer() {
        super(DockerImageName.parse("axllent/mailpit:v1.34.0"));
        withExposedPorts(SMTP_PORT, API_PORT);
    }

    /** Every message in the inbox, newest first, with its plain-text body. */
    public List<Mail> messages() {
        List<Mail> mails = new ArrayList<>();
        for (JsonNode summary : call("GET", "/api/v1/messages").path("messages")) {
            JsonNode message = call("GET", "/api/v1/message/" + summary.path("ID").asString());
            mails.add(new Mail(summary.path("To").path(0).path("Address").asString(),
                    summary.path("Subject").asString(), message.path("Text").asString()));
        }
        return mails;
    }

    public List<Mail> messagesTo(String address) {
        return messages().stream().filter(m -> m.to().equals(address)).toList();
    }

    public void clear() {
        call("DELETE", "/api/v1/messages");
    }

    private JsonNode call(String method, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + getHost() + ":" + getMappedPort(API_PORT) + path))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            // DELETE answers a plain "ok"; only reads return JSON.
            return "GET".equals(method) ? JSON.readTree(body) : JSON.createObjectNode();
        } catch (Exception e) {
            throw new IllegalStateException("Mailpit API call failed: " + method + " " + path, e);
        }
    }
}
