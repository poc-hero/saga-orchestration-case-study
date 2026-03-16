package com.pocmaster.site.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "metadata")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Metadata {

    @Id
    private String id;
    private String editor;

    @Field("app_version")
    @JsonProperty("app_version")
    private String appVersion;

    private String manager;
    private String description;
}
