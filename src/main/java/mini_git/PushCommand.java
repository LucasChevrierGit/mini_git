package mini_git;

import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@CommandLine.Command(name = "push", description = "Push local commits to GitHub")
public class PushCommand implements Runnable {

    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String API_BASE = "https://api.github.com/repos/";

    private final Path configPath = Path.of(".minigit", "config");
    private final Path headPath = Path.of(".minigit", "HEAD");
    private final Path objectsDir = Path.of(".minigit", "objects");

    @CommandLine.Option(names = {"--branch"}, description = "Branch to push to", defaultValue = "master")
    private String branch;

    @Override
    public void run() {
        Config config = new Config();
        config.load(configPath);

        String url = config.get("remote", "url");
        String token = config.get("remote", "token");

        if (url == null || token == null) {
            System.err.println("No remote configured. Use: minigit remote add origin <url> --token <token>");
            return;
        }

        String[] parts = url.replace("https://github.com/", "").split("/");
        if (parts.length < 2) {
            System.err.println("Invalid remote URL: " + url);
            return;
        }
        String owner = parts[0];
        String repo = parts[1];

        // Read the local HEAD commit
        String localCommitSha;
        try {
            localCommitSha = resolveHead();
        } catch (IOException e) {
            System.err.println("Failed to read HEAD: " + e.getMessage());
            return;
        }

        if (localCommitSha == null) {
            System.err.println("Nothing to push. Commit first with: minigit commit <message>");
            return;
        }

        // Read the local commit object
        String commitContent;
        try {
            commitContent = Files.readString(objectsDir.resolve(localCommitSha), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to read commit object: " + e.getMessage());
            return;
        }

        String treeSha = extractField(commitContent, "tree");
        String commitMessage = extractMessage(commitContent);

        // Read the local tree object
        String treeContent;
        try {
            treeContent = Files.readString(objectsDir.resolve(treeSha), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to read tree object: " + e.getMessage());
            return;
        }

        // Parse tree entries: "100644 <hash> <path>"
        List<String[]> treeEntries = new ArrayList<>();
        for (String line : treeContent.split("\n")) {
            String[] entryParts = line.split(" ", 3);
            if (entryParts.length == 3) {
                treeEntries.add(entryParts); // [mode, hash, path]
            }
        }

        if (treeEntries.isEmpty()) {
            System.err.println("Nothing to push. Empty tree.");
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        String apiBase = API_BASE + owner + "/" + repo;

        try {
            // Step 1: Get current remote HEAD ref
            String refResponse = getJson(client, apiBase + "/git/refs/heads/" + branch, token);
            String remoteHeadSha = extractJsonValue(refResponse, "sha");

            // Step 2: Get base tree SHA from remote
            String remoteCommitResponse = getJson(client, apiBase + "/git/commits/" + remoteHeadSha, token);
            String baseTreeSha = extractNestedJsonValue(remoteCommitResponse, "tree", "sha");

            // Step 3: Create a blob on GitHub for each file in the tree
            List<String> remoteTreeParts = new ArrayList<>();
            int success = 0;
            int failed = 0;

            for (String[] entry : treeEntries) {
                String blobHash = entry[1];
                String filePath = entry[2];

                try {
                    // Read blob content from local objects
                    byte[] content = Files.readAllBytes(objectsDir.resolve(blobHash));
                    String encoded = Base64.getEncoder().encodeToString(content);

                    String blobBody = "{\"content\":\"" + encoded + "\",\"encoding\":\"base64\"}";
                    String blobResponse = postJson(client, apiBase + "/git/blobs", token, blobBody);
                    String remoteBlobSha = extractJsonValue(blobResponse, "sha");

                    remoteTreeParts.add("{\"path\":\"" + escapeJson(filePath) + "\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"" + remoteBlobSha + "\"}");
                    System.out.println(ANSI_GREEN + "BLOB " + filePath + ANSI_RESET);
                    success++;
                } catch (Exception e) {
                    System.out.println(ANSI_RED + "FAIL " + filePath + " (" + e.getMessage() + ")" + ANSI_RESET);
                    failed++;
                }
            }

            if (remoteTreeParts.isEmpty()) {
                System.err.println("No blobs created. Nothing to push.");
                return;
            }

            // Step 4: Create tree on GitHub
            String treeBody = "{\"base_tree\":\"" + baseTreeSha + "\",\"tree\":[" + String.join(",", remoteTreeParts) + "]}";
            String treeResponse = postJson(client, apiBase + "/git/trees", token, treeBody);
            String newTreeSha = extractJsonValue(treeResponse, "sha");

            // Step 5: Create commit on GitHub
            String commitBody = "{\"message\":\"" + escapeJson(commitMessage) + "\",\"tree\":\"" + newTreeSha + "\",\"parents\":[\"" + remoteHeadSha + "\"]}";
            String newCommitResponse = postJson(client, apiBase + "/git/commits", token, commitBody);
            String newCommitSha = extractJsonValue(newCommitResponse, "sha");

            // Step 6: Update branch ref on GitHub
            String refBody = "{\"sha\":\"" + newCommitSha + "\"}";
            patchJson(client, apiBase + "/git/refs/heads/" + branch, token, refBody);

            System.out.println();
            System.out.println("Push complete: " + success + " files in commit " + newCommitSha.substring(0, 7));
            if (failed > 0) {
                System.out.println(ANSI_RED + failed + " files failed" + ANSI_RESET);
            }

        } catch (Exception e) {
            System.err.println("Push failed: " + e.getMessage());
        }
    }

    private String resolveHead() throws IOException {
        if (!Files.exists(headPath)) return null;

        String head = Files.readString(headPath).trim();
        if (head.startsWith("ref: ")) {
            Path refPath = Path.of(".minigit", head.substring(5));
            if (Files.exists(refPath)) {
                return Files.readString(refPath).trim();
            }
            return null;
        }
        return head;
    }

    private String extractField(String content, String field) {
        for (String line : content.split("\n")) {
            if (line.startsWith(field + " ")) {
                return line.substring(field.length() + 1);
            }
        }
        return null;
    }

    private String extractMessage(String content) {
        int emptyLine = content.indexOf("\n\n");
        if (emptyLine >= 0) {
            return content.substring(emptyLine + 2).trim();
        }
        return "minigit push";
    }

    private String getJson(HttpClient client, String url, String token) throws IOException, InterruptedException {
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

    private String postJson(HttpClient client, String url, String token, String body) throws IOException, InterruptedException {
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

    private String patchJson(HttpClient client, String url, String token, String body) throws IOException, InterruptedException {
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

    private String extractJsonValue(String json, String key) {
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

    private String extractNestedJsonValue(String json, String outerKey, String innerKey) {
        String searchKey = "\"" + outerKey + "\"";
        int outerIndex = json.indexOf(searchKey);
        if (outerIndex < 0) {
            throw new RuntimeException("Key \"" + outerKey + "\" not found in JSON response");
        }
        String remaining = json.substring(outerIndex);
        return extractJsonValue(remaining, innerKey);
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
