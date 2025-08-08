package com.table2table.orderservice.util;


import com.table2table.orderservice.dto.FoodPostResponse;
import com.table2table.orderservice.dto.FoodRequestResponseDto;
import com.table2table.orderservice.entity.FoodRequest;

public class FoodDtoConverterUtil {
    public static FoodRequestResponseDto convertToFoodRequestResponseDto(FoodRequest request) {
        FoodRequestResponseDto dto = new FoodRequestResponseDto();
        dto.setId(request.getId());
        dto.setFoodPostId(request.getFoodPostId());
        dto.setFoodTitle(request.getFoodTitle());
        dto.setPrice(request.getPrice());
        dto.setQuantity(request.getQuantity());

        dto.setCookId(request.getCookId());
        dto.setCustomerId(request.getRequestedUserId());
        dto.setCustomerName(request.getRequestorName());

        dto.setStatus(request.getStatus());
        dto.setRejectionReason(request.getRejectionReason());
        dto.setRequestedAt(request.getRequestedAt());

        return dto;
    }
}
