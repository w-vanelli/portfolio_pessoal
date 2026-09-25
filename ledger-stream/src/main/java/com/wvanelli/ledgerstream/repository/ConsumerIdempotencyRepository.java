package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.domain.ConsumerIdempotencyRecord;
import org.springframework.data.repository.CrudRepository;
import java.util.UUID;

public interface ConsumerIdempotencyRepository extends CrudRepository<ConsumerIdempotencyRecord, UUID> {
}
