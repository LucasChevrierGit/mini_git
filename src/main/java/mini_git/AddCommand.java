package mini_git;

import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;



@CommandLine.Command(name = "add", description = "Add files to the staging area")
public class AddCommand implements Runnable {

    @CommandLine.Parameters(description = "Files paths to add")
    private String[] paths;

    private final Path current_dir = Path.of(".");
    private final Path indexPath = Path.of(".minigit", "index");


    @Override
    public void run() {
        // Load current index as a map: path -> hash
        Map<String, String> indexed = loadIndex();
        Set<String> ignored = loadIgnore();
        List<String> filesToAdd = findFilesToAdd(paths, indexed, ignored);

        for (String file : filesToAdd) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] content = Files.readAllBytes(Path.of(file));
                byte[] hash = digest.digest(content);
                StringBuilder hashBuilder = new StringBuilder();
                for (byte b : hash) {
                    hashBuilder.append(String.format("%02x", b));
                }
                String hashS = hashBuilder.toString();

                // Skip if already staged with the same hash
                if (hashS.equals(indexed.get(file))) {
                    continue;
                }

                // Store blob
                Path objects = Path.of(".minigit", "objects", hashS);
                if (!Files.exists(objects)) {
                    Files.write(objects, content);
                }

                // Update index entry
                indexed.put(file, hashS);
                System.out.println("Added file: " + file);
            } catch (Exception e) {
                System.err.println("Failed to read file: " + e.getMessage());
            }
        }

        // Rewrite the index file
        writeIndex(indexed);
    }

    private Map<String, String> loadIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        if (Files.exists(indexPath)) {
            try {
                for (String line : Files.readAllLines(indexPath)) {
                    String[] parts = line.split(" ", 2);
                    if (parts.length == 2) {
                        index.put(parts[1], parts[0]); // path -> hash
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to read index: " + e.getMessage());
            }
        }
        return index;
    }

    private void writeIndex(Map<String, String> index) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : index.entrySet()) {
            sb.append(entry.getValue()).append(" ").append(entry.getKey()).append(System.lineSeparator());
        }
        try {
            Files.writeString(indexPath, sb.toString());
        } catch (IOException e) {
            System.err.println("Failed to write index: " + e.getMessage());
        }
    }

    private List<String> findFilesToAdd(String[] args, Map<String, String> indexed, Set<String> ignored) {
        boolean addAll = Arrays.asList(args).contains(".");

        try (Stream<Path> paths = Files.walk(this.current_dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(p -> current_dir.relativize(p).toString())
                    .filter(p -> !p.startsWith(".minigit"))
                    .filter(p -> !isIgnored(p, ignored))
                    .filter(p -> addAll || Arrays.asList(args).contains(p))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Failed to list files: " + e.getMessage());
        }

        return List.of();
    }

    private Set<String> loadIgnore() {
        Path ignorePath = Path.of(".minigit", "ignore");
        if (!Files.exists(ignorePath)) {
            return Collections.emptySet();
        }
        try {
            return Files.readAllLines(ignorePath).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toSet());
        } catch (IOException e) {
            return Collections.emptySet();
        }
    }

    private boolean isIgnored(String filePath, Set<String> patterns) {
        for (String pattern : patterns) {
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*");
            if (filePath.matches(regex) || filePath.matches(".*/" + regex)) {
                return true;
            }
        }
        return false;
    }
}
