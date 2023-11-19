/*
 *
 *  * Copyright (c) Crio.Do 2019. All rights reserved
 *
 */

package com.crio.qeats.repositories;

import com.crio.qeats.models.RestaurantEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface RestaurantRepository extends MongoRepository<RestaurantEntity, String> {

    // Add a query to search for restaurants by exact name (case-insensitive)
    @Query("{ 'name' : { $regex: '^?0$', $options: 'i' } }")
    Optional<List<RestaurantEntity>> findRestaurantsByNameExact(String name);

    @Query("{$or: [ { 'attributes': { $in: [?0] } }, { 'attributes': { $regex: ?0, $options: 'i' } } ] }")
    Optional<List<RestaurantEntity>> findRestaurantsByAttributes(String searchAttribute);
}
