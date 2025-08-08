package com.table2table.orderservice.dto;

import com.table2table.orderservice.dto.enums.RequestStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FoodRequestResponseDto {
    private Long id;
    private Long foodPostId;
    private String foodTitle;
    private Long customerId;
    private Double price;
    private String customerName;
    private Long cookId;
    private String cookName;
    private Integer quantity;
    private RequestStatus status;
    private String rejectionReason;
    private LocalDateTime createdAt;
    private LocalDateTime requestedAt;
}
