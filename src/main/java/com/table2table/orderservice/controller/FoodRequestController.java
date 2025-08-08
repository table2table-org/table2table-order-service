package com.table2table.orderservice.controller;

import com.table2table.orderservice.dto.CreateFoodRequestDto;
import com.table2table.orderservice.dto.FoodRequestResponseDto;
import com.table2table.orderservice.dto.enums.RequestStatus;
import com.table2table.orderservice.service.FoodRequestService;
import com.table2table.security.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/foodRequests")
@RequiredArgsConstructor
public class FoodRequestController {

    private final FoodRequestService foodRequestService;
    private final JwtService jwtService;

    @PostMapping("/createOrder")
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('ADMIN')")
    public ResponseEntity<FoodRequestResponseDto> createFoodRequest(
            @RequestBody CreateFoodRequestDto requestDto,
            @RequestHeader("Authorization") String authHeader
    ) {
        return ResponseEntity.ok(foodRequestService.createRequest(requestDto, authHeader));
    }

    @GetMapping("/getRequestForCook")
    @PreAuthorize("hasRole('COOK') or hasRole('ADMIN')")
    public ResponseEntity<List<FoodRequestResponseDto>> getRequestsForCook(
            @RequestHeader("Authorization") String authHeader
    ) {
        String token = authHeader.substring(7);
        String cookEmail = jwtService.extractUsername(token); // Helper method you already have
        return ResponseEntity.ok(foodRequestService.getRequestsForCook(cookEmail,authHeader));
    }

    @PutMapping("/{requestId}/updateStatus")
    @PreAuthorize("hasRole('COOK') or hasRole('ADMIN') ")
    public ResponseEntity<FoodRequestResponseDto> updateRequestStatus(
            @PathVariable Long requestId,
            @RequestParam RequestStatus status,
            @RequestParam(required = false) String rejectionReason,
            @RequestHeader("Authorization") String authHeader
    ) {
        return ResponseEntity.ok(foodRequestService.updateRequestStatus(requestId, status, rejectionReason, authHeader));
    }
}
