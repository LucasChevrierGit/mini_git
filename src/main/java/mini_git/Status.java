package src.main.java.mini_git;

import java.nio.file.Path;

public class Status {
    private String branch;
    private String HEAD;
    private boolean diverged;
    private Path[] staged;
    private Path[] unstaged;
    private Path[] untracked;

    Status(){
        update();
    }

    private void update(){
        // need to compare to remote branch
    }

    public String description(){
        return "Empty";
    }
}
