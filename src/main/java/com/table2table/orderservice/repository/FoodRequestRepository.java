package com.table2table.orderservice.repository;

import com.table2table.orderservice.entity.FoodRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FoodRequestRepository extends JpaRepository<FoodRequest, Long> {
    List<FoodRequest> findByCookId(Long cookId);
}
