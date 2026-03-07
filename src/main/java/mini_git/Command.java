package mini_git;

public interface Command {
    /**
     * Executes the command with the given arguments.
     * args[0] is the command name itself (e.g. "init", "add"),
     * args[1..n] are the command-specific arguments (e.g. filename for "add", message for "commit").
     */
    void execute(String[] args);
}