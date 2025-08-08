package com.table2table.orderservice.service;



import com.table2table.orderservice.dto.CreateFoodRequestDto;
import com.table2table.orderservice.dto.FoodRequestResponseDto;
import com.table2table.orderservice.dto.enums.RequestStatus;

import java.util.List;

public interface FoodRequestService {
    FoodRequestResponseDto createRequest(CreateFoodRequestDto dto, String authHeader);

    List<FoodRequestResponseDto> getRequestsForCook(String cookEmail, String authHeader);

    FoodRequestResponseDto updateRequestStatus(Long requestId, RequestStatus status, String rejectionReason, String authHeader);

    //List<FoodRequestResponseDto> getRequestsByUser(Long userId);
}
