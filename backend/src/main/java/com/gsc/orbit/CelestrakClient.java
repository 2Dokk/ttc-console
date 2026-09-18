package com.gsc.orbit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class CelestrakClient {

    private CelestrakClient() {
    }

    /** Returns {line1, line2} of the current GP element set for the NORAD id. */
    static String[] fetchTle(String urlTemplate, int noradId) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(urlTemplate.replace("{norad}", String.valueOf(noradId))))
                .timeout(Duration.ofSeconds(5))
                .build();
        String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        String line1 = null;
        String line2 = null;
        for (String raw : body.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("1 ")) {
                line1 = line;
            } else if (line.startsWith("2 ")) {
                line2 = line;
            }
        }
        if (line1 == null || line2 == null) {
            throw new IOException("no TLE in Celestrak response");
        }
        return new String[] {line1, line2};
    }
}
