
package com.crio.qeats.repositories;

import com.crio.qeats.models.ItemEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface ItemRepository extends MongoRepository<ItemEntity, String> {

    Optional<List<ItemEntity>> findByName(String name);

    @Query("{$or: [ { 'attributes': { $in: [?0] } }, { 'attributes': { $regex: ?0, $options: 'i' } } ] }")
    Optional<List<ItemEntity>> findItemsByAttributes(String searchAttribute);
}

