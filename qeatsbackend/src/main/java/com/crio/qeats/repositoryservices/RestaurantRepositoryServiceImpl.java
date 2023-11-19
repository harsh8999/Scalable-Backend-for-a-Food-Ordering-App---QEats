/*
 *
 *  * Copyright (c) Crio.Do 2019. All rights reserved
 *
 */

package com.crio.qeats.repositoryservices;

import ch.hsr.geohash.GeoHash;
import com.crio.qeats.configs.RedisConfiguration;
import com.crio.qeats.dto.Restaurant;
import com.crio.qeats.globals.GlobalConstants;
import com.crio.qeats.models.ItemEntity;
import com.crio.qeats.models.MenuEntity;
import com.crio.qeats.models.RestaurantEntity;
import com.crio.qeats.repositories.ItemRepository;
import com.crio.qeats.repositories.MenuRepository;
import com.crio.qeats.repositories.RestaurantRepository;
import com.crio.qeats.utils.GeoLocation;
import com.crio.qeats.utils.GeoUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Provider;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;

import redis.clients.jedis.Jedis;

@Service
@Primary
public class RestaurantRepositoryServiceImpl implements RestaurantRepositoryService {

  @Autowired
  private RedisConfiguration redisConfiguration;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private Provider<ModelMapper> modelMapperProvider;

  @Autowired
  private RestaurantRepository restaurantRepository;

  @Autowired
  private MenuRepository menuRepository;

  @Autowired
  private ItemRepository itemRepository;

  private boolean isOpenNow(LocalTime time, RestaurantEntity res) {
    LocalTime openingTime = LocalTime.parse(res.getOpensAt());
    LocalTime closingTime = LocalTime.parse(res.getClosesAt());

    return time.isAfter(openingTime) && time.isBefore(closingTime);
  }

  
  public List<Restaurant> findAllRestaurantsCloseBy(Double latitude,
      Double longitude, LocalTime currentTime, Double servingRadiusInKms) {
    List<Restaurant> restaurants = null;
    if (redisConfiguration.isCacheAvailable()) {
      restaurants = findAllRestaurantsCloseByFromCache(latitude, longitude, currentTime, servingRadiusInKms);
    } else {
      restaurants = findAllRestaurantsCloseFromDb(latitude, longitude, currentTime, servingRadiusInKms);
    }
    return restaurants;
  }


  private List<Restaurant> findAllRestaurantsCloseFromDb(Double latitude, Double longitude, LocalTime currentTime,
      Double servingRadiusInKms) {
    ModelMapper modelMapper = modelMapperProvider.get();
    List<RestaurantEntity> restaurantEntities = restaurantRepository.findAll();
    List<Restaurant> restaurants = new ArrayList<Restaurant>();
    for(RestaurantEntity restaurantEntity : restaurantEntities) {
      if (isRestaurantCloseByAndOpen(restaurantEntity, currentTime, latitude, longitude, servingRadiusInKms)) {
        restaurants.add(modelMapper.map(restaurantEntity, Restaurant.class));
      }
    }
    return restaurants;
  }


  private List<Restaurant> findAllRestaurantsCloseByFromCache(Double latitude, Double longitude, LocalTime currentTime,
      Double servingRadiusInKms) {
    List<Restaurant> restaurantList = new ArrayList<>();

    GeoLocation geoLocation = new GeoLocation(latitude, longitude);
    GeoHash geoHash = GeoHash.withCharacterPrecision(geoLocation.getLatitude(), geoLocation.getLongitude(), 7);

    try (Jedis jedis = redisConfiguration.getJedisPool().getResource()) {
      String jsonStringFromCache = jedis.get(geoHash.toBase32());

      if (jsonStringFromCache == null) {
        // Cache needs to be updated.
        String createdJsonString = "";
        try {
          restaurantList = findAllRestaurantsCloseFromDb(geoLocation.getLatitude(), geoLocation.getLongitude(),
              currentTime, servingRadiusInKms);
          createdJsonString = new ObjectMapper().writeValueAsString(restaurantList);
        } catch (JsonProcessingException e) {
          e.printStackTrace();
        }

        // Do operations with jedis resource
        jedis.setex(geoHash.toBase32(), GlobalConstants.REDIS_ENTRY_EXPIRY_IN_SECONDS, createdJsonString);
      } else {
        try {
          restaurantList = new ObjectMapper().readValue(jsonStringFromCache, new TypeReference<List<Restaurant>>() {
          });
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    return restaurantList;
  }

  /**
   * Utility method to check if a restaurant is within the serving radius at a given time.
   * @return boolean True if restaurant falls within serving radius and is open, false otherwise
   */
  private boolean isRestaurantCloseByAndOpen(RestaurantEntity restaurantEntity,
      LocalTime currentTime, Double latitude, Double longitude, Double servingRadiusInKms) {
    if (isOpenNow(currentTime, restaurantEntity)) {
      return GeoUtils.findDistanceInKm(latitude, longitude,
          restaurantEntity.getLatitude(), restaurantEntity.getLongitude())
          < servingRadiusInKms;
    }

    return false;
  }

  @Override
  public List<Restaurant> findRestaurantsByName(Double latitude, Double longitude,
      String searchString, LocalTime currentTime, Double servingRadiusInKms) {
    ModelMapper mapper = modelMapperProvider.get();
    List<RestaurantEntity> restaurantEntities = restaurantRepository.findRestaurantsByNameExact(searchString).get();
    List<Restaurant> restaurants = new ArrayList<Restaurant>();
    for(RestaurantEntity restaurantEntity : restaurantEntities) {
      if(isRestaurantCloseByAndOpen(restaurantEntity, currentTime, latitude, longitude, servingRadiusInKms)) {
        restaurants.add(mapper.map(restaurantEntity, Restaurant.class));
      }
    }
    return restaurants;
  }

  @Override
  public List<Restaurant> findRestaurantsByAttributes(Double latitude, Double longitude,
      String searchString, LocalTime currentTime, Double servingRadiusInKms) {
    ModelMapper mapper = modelMapperProvider.get();
    List<RestaurantEntity> restaurantEntities = restaurantRepository.findRestaurantsByAttributes(searchString).get();
    List<Restaurant> restaurants = new ArrayList<Restaurant>();
    for(RestaurantEntity restaurantEntity : restaurantEntities) {
      if(isRestaurantCloseByAndOpen(restaurantEntity, currentTime, latitude, longitude, servingRadiusInKms)) {
        restaurants.add(mapper.map(restaurantEntity, Restaurant.class));
      }
    }
    return restaurants;
  }

  @Override
  public List<Restaurant> findRestaurantsByItemName(Double latitude, Double longitude,
      String searchString, LocalTime currentTime, Double servingRadiusInKms) {
    ModelMapper modelMapper = modelMapperProvider.get();
    List<ItemEntity> items = itemRepository.findByName(searchString).get();
    List<String> itemIdList = new ArrayList<>();
    items.forEach(e -> itemIdList.add(e.getId()));
    List<MenuEntity> menuItems = menuRepository.findMenusByItemsItemIdIn(itemIdList).get();
    List<String> restaurantIds = menuItems.stream().map(e -> e.getRestaurantId()).collect(Collectors.toList());
    List<RestaurantEntity> restaurantEntities = new ArrayList<>();
    restaurantRepository.findAllById(restaurantIds).forEach(e -> restaurantEntities.add(e));

    List<Restaurant> restaurants = new ArrayList<Restaurant>();
      for (RestaurantEntity restaurantEntity : restaurantEntities) {
        if (isRestaurantCloseByAndOpen(restaurantEntity, currentTime, latitude, longitude, servingRadiusInKms)) {
          restaurants.add(modelMapper.map(restaurantEntity, Restaurant.class));
        }
      }
    return restaurants;
  }

  @Override
  public List<Restaurant> findRestaurantsByItemAttributes(Double latitude, Double longitude,
      String searchString, LocalTime currentTime, Double servingRadiusInKms) {
    ModelMapper modelMapper = modelMapperProvider.get();
    List<ItemEntity> items = itemRepository.findItemsByAttributes(searchString).get();
    List<String> itemIdList = new ArrayList<>();
    items.forEach(e -> itemIdList.add(e.getId()));
    List<MenuEntity> menuItems = menuRepository.findMenusByItemsItemIdIn(itemIdList).get();
    List<String> restaurantIds = menuItems.stream().map(e -> e.getRestaurantId()).collect(Collectors.toList());
    List<RestaurantEntity> restaurantEntities = new ArrayList<>();
    restaurantRepository.findAllById(restaurantIds).forEach(e -> restaurantEntities.add(e));

    List<Restaurant> restaurants = new ArrayList<Restaurant>();
      for (RestaurantEntity restaurantEntity : restaurantEntities) {
        if (isRestaurantCloseByAndOpen(restaurantEntity, currentTime, latitude, longitude, servingRadiusInKms)) {
          restaurants.add(modelMapper.map(restaurantEntity, Restaurant.class));
        }
      }
    return restaurants;
  }



}

