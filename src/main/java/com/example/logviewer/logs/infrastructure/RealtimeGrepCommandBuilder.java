package com.example.logviewer.logs.infrastructure;

public final class RealtimeGrepCommandBuilder {

    private RealtimeGrepCommandBuilder() {
    }

    public static String wrap(String sourceCommand, TailFilterOptions options) {
        if (options == null || !options.hasKeyword()) {
            return sourceCommand;
        }
        StringBuilder shell = new StringBuilder();
        shell.append("(\n");
        shell.append(sourceCommand);
        if (!sourceCommand.endsWith("\n")) {
            shell.append('\n');
        }
        shell.append(") | grep --line-buffered ");
        if (!options.caseSensitive()) {
            shell.append("-i ");
        }
        if (options.contextLines() > 0) {
            shell.append("-C ").append(options.contextLines()).append(' ');
        }
        shell.append("--no-group-separator -F -- ");
        shell.append(ShellQuoter.sq(options.keyword()));
        shell.append(" || true\n");
        return shell.toString();
    }
}
