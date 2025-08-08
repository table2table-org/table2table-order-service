package com.table2table.orderservice.entity;

import com.table2table.orderservice.dto.enums.RequestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "food_requests")
public class FoodRequest {
    public FoodRequest(int quantity, RequestStatus status, String rejectionReason
            , LocalDateTime requestedAt, boolean paymentSuccessful, Long foodPostId
            , Long requestedUserId, LocalDateTime createdAt, Long cookId, String foodTitle
            , String requestorName, Double price) {
        this.quantity = quantity;
        this.status = status;
        this.rejectionReason = rejectionReason;
        this.requestedAt = requestedAt;
        this.paymentSuccessful = paymentSuccessful;
        this.foodPostId = foodPostId;
        this.requestedUserId = requestedUserId;
        this.createdAt = createdAt;
        this.cookId = cookId;
        this.foodTitle = foodTitle;
        this.requestorName = requestorName;
        this.price= price;
    }

    public FoodRequest() {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int quantity;

    private Double price;

    @Enumerated(EnumType.STRING)
    private RequestStatus status; // PENDING, ACCEPTED, REJECTED, REFUND_INITIATED

    private String rejectionReason;

    private LocalDateTime requestedAt;

    private boolean paymentSuccessful;

    private Long foodPostId;

    private String foodTitle;

    private Long cookId;

    private Long requestedUserId;

    private String requestorName;

    private LocalDateTime createdAt;

    private Long paymentId;

    private String transactionId;

}
