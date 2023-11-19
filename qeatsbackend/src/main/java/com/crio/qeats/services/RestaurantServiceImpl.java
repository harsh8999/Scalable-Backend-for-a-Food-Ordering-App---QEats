
/*
 *
 *  * Copyright (c) Crio.Do 2019. All rights reserved
 *
 */

package com.crio.qeats.services;

import com.crio.qeats.dto.Restaurant;
import com.crio.qeats.exchanges.GetRestaurantsRequest;
import com.crio.qeats.exchanges.GetRestaurantsResponse;
import com.crio.qeats.repositoryservices.RestaurantRepositoryService;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Log4j2
public class RestaurantServiceImpl implements RestaurantService {

  private final Double peakHoursServingRadiusInKms = 3.0;
  private final Double normalHoursServingRadiusInKms = 5.0;
  @Autowired
  private RestaurantRepositoryService restaurantRepositoryService;


  // TODO: CRIO_TASK_MODULE_RESTAURANTSAPI - Implement findAllRestaurantsCloseby.
  // Check RestaurantService.java file for the interface contract.
  // For peak hours: 8AM - 10AM, 1PM-2PM, 7PM-9PM
  //  * - service radius is 3KMs.
  //  * - All other times, serving radius is 5KMs.
  @Override
  public GetRestaurantsResponse findAllRestaurantsCloseBy(
      GetRestaurantsRequest getRestaurantsRequest, LocalTime currentTime) {
        log.info("Finding all restaurants close by for request: {} at time: {}", getRestaurantsRequest, currentTime);

        Double servingRadiusInKms = normalHoursServingRadiusInKms;

        if (isPeakHour(currentTime)) {
          servingRadiusInKms = peakHoursServingRadiusInKms;
        }
    
        List<Restaurant> restaurants = restaurantRepositoryService.findAllRestaurantsCloseBy(
              getRestaurantsRequest.getLatitude(),
              getRestaurantsRequest.getLongitude(),
              currentTime,
              servingRadiusInKms);
    
        GetRestaurantsResponse response = new GetRestaurantsResponse(restaurants);
        // response.setRestaurants(restaurants);
        log.info("Found {} restaurants for request: {} at time: {}", restaurants.size(), getRestaurantsRequest, currentTime);
        return response;
  }

  /**
   * Determines whether the given time is a peak hour.
   *
   * @param time the time to check
   * @return true if the time is a peak hour, false otherwise
   */
  private boolean isPeakHour(LocalTime time) {
    int hour = time.getHour();
    return (hour >= 8 && hour <= 10) || (hour >= 13 && hour <= 14) || (hour >= 19 && hour <= 21);
  }

  @Override
  public GetRestaurantsResponse findRestaurantsBySearchQuery(
      GetRestaurantsRequest getRestaurantsRequest, LocalTime currentTime) {
    log.info("Finding restaurants by search query for request: {} at time: {}", getRestaurantsRequest, currentTime);

    if(getRestaurantsRequest.getSearchFor().isEmpty()) {
      log.info("Search query for request: {} at time: {} is empty!", getRestaurantsRequest, currentTime);
      return new GetRestaurantsResponse();
    }

    Double servingRadiusInKms = isPeakHour(currentTime) ? peakHoursServingRadiusInKms : normalHoursServingRadiusInKms;

    // // Retrieve all restaurants within the specified serving radius
    // List<Restaurant> allRestaurants = restaurantRepositoryService.findAllRestaurantsCloseBy(
    //     getRestaurantsRequest.getLatitude(), getRestaurantsRequest.getLongitude(), currentTime, servingRadiusInKms);

    // Filter and order restaurants based on the search query
    List<Restaurant> filteredRestaurants = filterAndOrderRestaurantsBySearchQuery(getRestaurantsRequest.getSearchFor(), currentTime,
        getRestaurantsRequest.getLatitude(), getRestaurantsRequest.getLongitude(), servingRadiusInKms);

    GetRestaurantsResponse response = new GetRestaurantsResponse(filteredRestaurants);
    log.info("Found {} restaurants matching search query for request: {} at time: {}", filteredRestaurants.size(), getRestaurantsRequest, currentTime);

    return response;
  }


  private List<Restaurant> filterAndOrderRestaurantsBySearchQuery(String searchQuery,
                                                              LocalTime currentTime, Double latitude, Double longitude,
                                                              Double servingRadiusInKms) {
    List<Restaurant> filteredRestaurants = new ArrayList<>();

    // Use the provided methods to search by name, attributes, item name, and item attributes
    List<Restaurant> nameMatches = restaurantRepositoryService.findRestaurantsByName(latitude, longitude, searchQuery, currentTime, servingRadiusInKms);
    List<Restaurant> attributeMatches = restaurantRepositoryService.findRestaurantsByAttributes(latitude, longitude, searchQuery, currentTime, servingRadiusInKms);
    List<Restaurant> itemNameMatches = restaurantRepositoryService.findRestaurantsByItemName(latitude, longitude, searchQuery, currentTime, servingRadiusInKms);
    List<Restaurant> itemAttributeMatches = restaurantRepositoryService.findRestaurantsByItemAttributes(latitude, longitude, searchQuery, currentTime, servingRadiusInKms);

    // Combine and order the results based on your specified rules
    filteredRestaurants.addAll(nameMatches);
    filteredRestaurants.addAll(attributeMatches);
    filteredRestaurants.addAll(itemNameMatches);
    filteredRestaurants.addAll(itemAttributeMatches);

    return filteredRestaurants;
}

  @Override
  public GetRestaurantsResponse findRestaurantsBySearchQueryMt(
      GetRestaurantsRequest getRestaurantsRequest, LocalTime currentTime) {
    log.info("Finding restaurants by search query (multithreaded) for request: {} at time: {}", getRestaurantsRequest, currentTime);

    if (getRestaurantsRequest.getSearchFor().isEmpty()) {
        log.info("Search query for request: {} at time: {} is empty!", getRestaurantsRequest, currentTime);
        return new GetRestaurantsResponse();
    }

    Double servingRadiusInKms = isPeakHour(currentTime) ? peakHoursServingRadiusInKms : normalHoursServingRadiusInKms;

    //---------
    // Parallize this part
    ExecutorService executor = Executors.newFixedThreadPool(4);
    List<Future<List<Restaurant>>> futures = new ArrayList<>();
    Future<List<Restaurant>> nameMatchesFutures = executor.submit(() -> restaurantRepositoryService.findRestaurantsByName(getRestaurantsRequest.getLatitude(), getRestaurantsRequest.getLongitude(), getRestaurantsRequest.getSearchFor(), currentTime, servingRadiusInKms));
    Future<List<Restaurant>> attributeMatchesFutures = executor.submit(() -> restaurantRepositoryService.findRestaurantsByAttributes(getRestaurantsRequest.getLatitude(), getRestaurantsRequest.getLongitude(), getRestaurantsRequest.getSearchFor(), currentTime, servingRadiusInKms));
    Future<List<Restaurant>> itemNameMatchesFutures = executor.submit(() -> restaurantRepositoryService.findRestaurantsByItemName(getRestaurantsRequest.getLatitude(), getRestaurantsRequest.getLongitude(), getRestaurantsRequest.getSearchFor(), currentTime, servingRadiusInKms));
    Future<List<Restaurant>> itemAttributeMatchesFutures = executor.submit(() -> restaurantRepositoryService.findRestaurantsByItemAttributes(getRestaurantsRequest.getLatitude(), getRestaurantsRequest.getLongitude(), getRestaurantsRequest.getSearchFor(), currentTime, servingRadiusInKms));

    futures.add(nameMatchesFutures);
    futures.add(attributeMatchesFutures);
    futures.add(itemNameMatchesFutures);
    futures.add(itemAttributeMatchesFutures);

    List<Restaurant> restaurantsList = new ArrayList<>(); 
    for(Future<List<Restaurant>> future: futures) {
      try {
        future.get().forEach(e -> restaurantsList.add(e));
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e.getMessage());
      } catch (Exception e) {
        throw new RuntimeException("Exception!!!");
      }
    }

    // shutdown the executor
    executor.shutdown();
    try {
        if (!executor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
        } 
    } catch (InterruptedException e) {
        executor.shutdownNow();
    }
    
    GetRestaurantsResponse response = new GetRestaurantsResponse(restaurantsList);
    log.info("Found {} restaurants matching search query (multithreaded) for request: {} at time: {}",
        restaurantsList.size(), getRestaurantsRequest, currentTime);

    return response;
  }

  
}

