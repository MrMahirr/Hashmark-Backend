package dev.hashmark.repo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GitHubRepoDto {
    private String id;
    
    @JsonProperty("full_name")
    private String fullName;
    
    @JsonProperty("private")
    private Boolean isPrivate;
}
