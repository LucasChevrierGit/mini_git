package mini_git;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(name = "status", description = "Show the working tree status")
public class StatusCommand implements Runnable {

    @Override
    public void run() {
        Path root = Path.of(".minigit");

        if (!Files.exists(root)) {
            System.err.println("Not a mini_git repository (no .minigit directory).");
            return;
        }

        Path indexPath = root.resolve("index");
        Set<String> indexed = loadIndex(indexPath);
        Set<String> working = listWorkingFiles();

        Set<String> untracked = new TreeSet<>(working);
        untracked.removeAll(indexed);

        Set<String> tracked = new TreeSet<>(indexed);
        tracked.retainAll(working);

        Set<String> deleted = new TreeSet<>(indexed);
        deleted.removeAll(working);

        if (!untracked.isEmpty()) {
            System.out.println("Untracked files:");
            untracked.forEach(f -> System.out.println("  " + f));
        }

        if (!deleted.isEmpty()) {
            System.out.println("Deleted files:");
            deleted.forEach(f -> System.out.println("  " + f));
        }

        if (untracked.isEmpty() && deleted.isEmpty()) {
            System.out.println("Nothing to report, working tree clean.");
        }
    }

    private Set<String> loadIndex(Path indexPath) {
        if (!Files.exists(indexPath)) {
            return Collections.emptySet();
        }
        try {
            return Files.readAllLines(indexPath).stream()
                .map(line -> line.split(" ", 2))
                .filter(parts -> parts.length == 2)
                .map(parts -> parts[1])
                .collect(Collectors.toSet());
        } catch (IOException e) {
            System.err.println("Failed to read index: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    private Set<String> listWorkingFiles() {
        try (Stream<Path> paths = Files.walk(Path.of("."))) {
            return paths
                .filter(Files::isRegularFile)
                .map(p -> Path.of(".").relativize(p).toString())
                .filter(p -> !p.startsWith(".minigit"))
                .collect(Collectors.toSet());
        } catch (IOException e) {
            System.err.println("Failed to list working files: " + e.getMessage());
            return Collections.emptySet();
        }
    }
}
