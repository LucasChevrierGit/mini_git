package mini_git;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Json {

    public static String patchJson(HttpClient client, String url, String token, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("PATCH " + url + " returned " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    public static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) {
            throw new RuntimeException("Key \"" + key + "\" not found in JSON response");
        }
        int colonIndex = json.indexOf(":", keyIndex + searchKey.length());
        int start = json.indexOf("\"", colonIndex + 1) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    public static String extractNestedJsonValue(String json, String outerKey, String innerKey) {
        String searchKey = "\"" + outerKey + "\"";
        int outerIndex = json.indexOf(searchKey);
        if (outerIndex < 0) {
            throw new RuntimeException("Key \"" + outerKey + "\" not found in JSON response");
        }
        String remaining = json.substring(outerIndex);
        return extractJsonValue(remaining, innerKey);
    }

    public static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }


    public static String getJson(HttpClient client, String url, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GET " + url + " returned " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /**
     * Extract all objects from a JSON array field, returning the raw JSON of each array element.
     * E.g. for {"tree":[{...},{...}]}, extractJsonArray(json, "tree") returns list of "{...}" strings.
     */
    public static java.util.List<String> extractJsonArray(String json, String key) {
        java.util.List<String> items = new java.util.ArrayList<>();
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return items;
        int bracketStart = json.indexOf("[", keyIndex);
        if (bracketStart < 0) return items;

        int depth = 0;
        int itemStart = -1;
        for (int i = bracketStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 1) itemStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 1 && itemStart >= 0) {
                    items.add(json.substring(itemStart, i + 1));
                    itemStart = -1;
                }
            } else if (c == '[') {
                if (depth == 0) depth = 1;
                else depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) break;
            }
        }
        return items;
    }

    public static String postJson(HttpClient client, String url, String token, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("POST " + url + " returned " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

}
