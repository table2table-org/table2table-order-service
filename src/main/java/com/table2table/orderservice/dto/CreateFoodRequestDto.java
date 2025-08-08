package com.table2table.orderservice.dto;

import lombok.Data;

@Data
public class CreateFoodRequestDto {
    private Long foodPostId;
    private Integer quantity;
}
