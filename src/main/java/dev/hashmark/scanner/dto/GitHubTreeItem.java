package dev.hashmark.scanner.dto;

import lombok.Data;

@Data
public class GitHubTreeItem {
    private String path;
    private String type;
}
