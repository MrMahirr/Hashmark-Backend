package dev.hashmark.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GitHubRepositoryMetadata {

    @JsonProperty("default_branch")
    private String defaultBranch;
}
