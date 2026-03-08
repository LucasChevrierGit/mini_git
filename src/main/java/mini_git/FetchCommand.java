package mini_git;

import picocli.CommandLine;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@CommandLine.Command(name = "fetch", description = "Fetch updates from remote repository")
public class FetchCommand implements Runnable {

    @CommandLine.Option(names = {"--branch"}, description = "Branch to fetch", defaultValue = "master")
    private String branch;

    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String API_BASE = "https://api.github.com/repos/";

    private final Path configPath = Path.of(".minigit", "config");
    private final Path objectsDir = Path.of(".minigit", "objects");

    @Override
    public void run() {
        Config config = new Config();
        config.load(configPath);
        final String url = config.get("remote", "url");
        final String token = config.get("remote", "token");
        branch = (branch == null || branch.isEmpty()) ? config.get("remote", "branch") : branch;

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
        String repo = parts[1].replaceAll("\\.git$", "");

        HttpClient client = HttpClient.newHttpClient();
        String apiBase = API_BASE + owner + "/" + repo;

        try {
            String refResponse = Json.getJson(client, apiBase + "/git/refs/heads/" + branch, token);
            String remoteHeadSha = Json.extractJsonValue(refResponse, "sha");
            System.out.println("Remote HEAD: " + remoteHeadSha.substring(0, 7));

            String commitResponse = Json.getJson(client, apiBase + "/git/commits/" + remoteHeadSha, token);
            String remoteTreeSha = Json.extractNestedJsonValue(commitResponse, "tree", "sha");
            String commitMessage = Json.extractJsonValue(commitResponse, "message");

            String parentSha = null;
            int parentsIdx = commitResponse.indexOf("\"parents\"");
            if (parentsIdx >= 0) {
                String parentsSection = commitResponse.substring(parentsIdx);
                int bracketStart = parentsSection.indexOf("[");
                int bracketEnd = parentsSection.indexOf("]");
                if (bracketStart >= 0 && bracketEnd > bracketStart) {
                    String inner = parentsSection.substring(bracketStart, bracketEnd + 1);
                    if (inner.contains("\"sha\"")) {
                        parentSha = Json.extractJsonValue(inner, "sha");
                    }
                }
            }


            String treeResponse = Json.getJson(client, apiBase + "/git/trees/" + remoteTreeSha + "?recursive=1", token);
            List<String> treeEntries = Json.extractJsonArray(treeResponse, "tree");

            StringBuilder localTreeContent = new StringBuilder();
            int fetched = 0;
            for (String entry : treeEntries) {
                String type = Json.extractJsonValue(entry, "type");
                if (!"blob".equals(type)) continue;

                String blobPath = Json.extractJsonValue(entry, "path");
                String remoteBlobSha = Json.extractJsonValue(entry, "sha");

                String blobResponse = Json.getJson(client, apiBase + "/git/blobs/" + remoteBlobSha, token);
                String base64Content = Json.extractJsonValue(blobResponse, "content");

                base64Content = base64Content.replace("\\n", "").replace("\n", "").replace("\r", "");
                byte[] rawContent = Base64.getDecoder().decode(base64Content);

                String localBlobSha = sha1Hex(rawContent);
                Path blobObjectPath = objectsDir.resolve(localBlobSha);
                if (!Files.exists(blobObjectPath)) {
                    Files.write(blobObjectPath, rawContent);
                }

                localTreeContent.append("100644 ").append(localBlobSha).append(" ").append(blobPath).append("\n");
                System.out.println(ANSI_GREEN + "FETCH " + blobPath + ANSI_RESET);
                fetched++;
            }


            String localTreeSha = writeObject(localTreeContent.toString());

            StringBuilder commitContent = new StringBuilder();
            commitContent.append("tree ").append(localTreeSha).append("\n");
            if (parentSha != null) {
                commitContent.append("parent ").append(parentSha).append("\n");
            }
            commitContent.append("timestamp ").append(Instant.now().getEpochSecond()).append("\n");
            commitContent.append("\n");
            commitContent.append(commitMessage).append("\n");

            String localCommitSha = writeObject(commitContent.toString());

            Path remoteRefPath = Path.of(".minigit", "refs", "remotes", "origin", branch);
            Files.createDirectories(remoteRefPath.getParent());
            Files.writeString(remoteRefPath, localCommitSha + "\n");

            System.out.println();
            System.out.println("Fetch complete: " + fetched + " files from " + branch);
            System.out.println("Remote ref: " + localCommitSha.substring(0, 7) + " -> .minigit/refs/remotes/origin/" + branch);

        } catch (Exception e) {
            System.err.println("Fetch failed: " + e.getMessage());
        }
    }

    private String writeObject(String content) throws IOException, NoSuchAlgorithmException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String sha = sha1Hex(bytes);
        Path objectPath = objectsDir.resolve(sha);
        if (!Files.exists(objectPath)) {
            Files.write(objectPath, bytes);
        }
        return sha;
    }

    private static String sha1Hex(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
