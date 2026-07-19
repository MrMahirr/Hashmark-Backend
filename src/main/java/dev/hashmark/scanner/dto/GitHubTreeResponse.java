package dev.hashmark.scanner.dto;

import java.util.List;
import lombok.Data;

@Data
public class GitHubTreeResponse {
    private List<GitHubTreeItem> tree = List.of();
}
