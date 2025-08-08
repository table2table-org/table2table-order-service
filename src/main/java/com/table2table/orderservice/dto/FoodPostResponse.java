package com.table2table.orderservice.dto;

import com.table2table.orderservice.dto.enums.FoodStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FoodPostResponse {
    private Long id;
    private Long cookId;
    private Long communityId;
    private String title;
    private String description;
    private Double price;
    private Integer quantity;
    private LocalDateTime expiresAt;
    private LocalDateTime postedAt;
    private String imageUrl;
    private FoodStatus status;
    private String postedBy; // maybe postedBy user name or email
}
