package com.pocmaster.site.metadata;

import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mongo")
public interface MetadataRepository extends ReactiveMongoRepository<Metadata, String> {
}
