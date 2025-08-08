package com.table2table.orderservice.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentRequestDto {

    private Long paymentId;

    private Long foodRequestId; // Assuming FoodRequest entity exists

    private BigDecimal amount;
}
