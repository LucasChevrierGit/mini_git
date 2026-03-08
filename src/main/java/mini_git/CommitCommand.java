package mini_git;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

@CommandLine.Command(name = "commit", description = "Commit staged files locally")
public class CommitCommand implements Runnable {

    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RESET = "\u001B[0m";

    private final Path indexPath = Path.of(".minigit", "index");
    private final Path objectsDir = Path.of(".minigit", "objects");
    private final Path headPath = Path.of(".minigit", "HEAD");

    @CommandLine.Parameters(description = "Message for the commit")
    private String message;

    @Override
    public void run() {
        if (!Files.exists(indexPath)) {
            System.err.println("Nothing to commit. Stage files first with: minigit add <files>");
            return;
        }

        List<String> indexLines;
        try {
            indexLines = Files.readAllLines(indexPath);
        } catch (IOException e) {
            System.err.println("Failed to read index: " + e.getMessage());
            return;
        }

        if (indexLines.isEmpty()) {
            System.err.println("Nothing to commit. Stage files first with: minigit add <files>");
            return;
        }

        try {
            // Step 1: Build tree object from the index
            // Format: one line per entry: "<mode> <hash> <path>"
            StringBuilder treeContent = new StringBuilder();
            for (String line : indexLines) {
                String[] parts = line.split(" ", 2);
                if (parts.length < 2) continue;
                String hash = parts[0];
                String filePath = parts[1];
                treeContent.append("100644 ").append(hash).append(" ").append(filePath).append("\n");
            }

            String treeSha = writeObject(treeContent.toString());

            // Step 2: Build commit object
            String parentSha = resolveHead();

            StringBuilder commitContent = new StringBuilder();
            commitContent.append("tree ").append(treeSha).append("\n");
            if (parentSha != null) {
                commitContent.append("parent ").append(parentSha).append("\n");
            }
            commitContent.append("timestamp ").append(Instant.now().getEpochSecond()).append("\n");
            commitContent.append("\n");
            commitContent.append(message).append("\n");

            String commitSha = writeObject(commitContent.toString());

            // Step 3: Update ref to point to new commit
            updateHead(commitSha);

            // Step 4: Clear the index
            Files.writeString(indexPath, "");

            System.out.println(ANSI_GREEN + "[" + getRefName() + " " + commitSha.substring(0, 7) + "] " + message + ANSI_RESET);
            System.out.println(indexLines.size() + " file(s) committed");

        } catch (Exception e) {
            System.err.println("Commit failed: " + e.getMessage());
        }
    }

    private String writeObject(String content) throws IOException, NoSuchAlgorithmException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(bytes);
        String sha = toHex(hash);

        Path objectPath = objectsDir.resolve(sha);
        if (!Files.exists(objectPath)) {
            Files.write(objectPath, bytes);
        }
        return sha;
    }

    private String resolveHead() throws IOException {
        if (!Files.exists(headPath)) return null;

        String head = Files.readString(headPath).trim();
        if (head.startsWith("ref: ")) {
            Path refPath = Path.of(".minigit", head.substring(5));
            if (Files.exists(refPath)) {
                return Files.readString(refPath).trim();
            }
            return null; // first commit on this branch
        }
        return head; // detached HEAD
    }

    private void updateHead(String commitSha) throws IOException {
        String head = Files.readString(headPath).trim();
        if (head.startsWith("ref: ")) {
            Path refPath = Path.of(".minigit", head.substring(5));
            Files.createDirectories(refPath.getParent());
            Files.writeString(refPath, commitSha + "\n");
        } else {
            Files.writeString(headPath, commitSha + "\n");
        }
    }

    private String getRefName() {
        try {
            String head = Files.readString(headPath).trim();
            if (head.startsWith("ref: refs/")) {
                return head.substring("ref: refs/".length());
            }
            return head.substring(0, 7);
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
