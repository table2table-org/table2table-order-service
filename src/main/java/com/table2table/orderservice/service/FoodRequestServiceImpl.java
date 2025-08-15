package com.table2table.orderservice.service;


import com.table2table.orderservice.dto.*;
import com.table2table.orderservice.dto.enums.RequestStatus;
import com.table2table.orderservice.entity.FoodRequest;
import com.table2table.orderservice.repository.FoodRequestRepository;
import com.table2table.orderservice.util.FoodDtoConverterUtil;
import com.table2table.security.constants.Table2tableServiceURLs;
import com.table2table.security.dto.UserResponseDto;
import com.table2table.security.events.FoodRequestEvent;
import com.table2table.security.events.OrderStatusEvent;
import com.table2table.security.events.PaymentEvent;
import com.table2table.security.exceptions.InsufficientQuantityException;
import com.table2table.security.exceptions.ResourceNotFoundException;
import com.table2table.security.service.JwtService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FoodRequestServiceImpl implements FoodRequestService {

    private final FoodRequestRepository foodRequestRepository;
    private final JwtService jwtService;
    private final WebClient.Builder webClientBuilder;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    @Transactional
    public FoodRequestResponseDto createRequest(CreateFoodRequestDto dto, String authHeader) {
        try {
            log.info("Creating food request: quantity={}, foodPostId={}", dto.getQuantity(), dto.getFoodPostId());

            // 1. Extract and validate user from token
            String token = authHeader.substring(7);
            String email = jwtService.extractUsername(token);

            UserResponseDto user = webClientBuilder.build()
                    .get()
                    .uri(Table2tableServiceURLs.GET_USER_BY_EMAIL + "{email}", email)
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .retrieve()
                    .bodyToMono(UserResponseDto.class)
                    .block();

            if (user == null) {
                throw new RuntimeException("User not found");
            }

            // 2. Retrieve the food post info
            FoodPostResponse foodPost = webClientBuilder.build()
                    .get()
                    .uri(Table2tableServiceURLs.GET_FOOD_POST_BY_ID + "{id}", dto.getFoodPostId())
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .retrieve()
                    .bodyToMono(FoodPostResponse.class)
                    .block();

            if (foodPost == null || foodPost.getQuantity() <= 0) {
                throw new RuntimeException("Food not available");
            }

            // 3. Create new food request with PENDING status
            FoodRequest foodRequest = new FoodRequest(
                    dto.getQuantity(),
                    RequestStatus.PENDING,
                    null, // rejection reason
                    LocalDateTime.now(),
                    false, // payment successful
                    foodPost.getId(),
                    user.getId(),
                    LocalDateTime.now(),
                    foodPost.getCookId(),
                    foodPost.getTitle(),
                    user.getName(),
                    foodPost.getPrice()
            );

            // 4. Save food request first to get ID
            FoodRequest savedRequest = foodRequestRepository.save(foodRequest);
            log.info("Saved FoodRequest with ID: {}", savedRequest.getId());

            // 5. Process payment with the saved request ID
            PaymentRequestDto paymentRequestDto = new PaymentRequestDto();
            paymentRequestDto.setFoodRequestId(savedRequest.getId());
            paymentRequestDto.setAmount(BigDecimal.valueOf(savedRequest.getPrice()));

            PaymentResponseDto paymentSuccess = simulatePayment(paymentRequestDto, authHeader);

            if (paymentSuccess == null) {
                // Payment failed - update status and don't proceed
                savedRequest.setStatus(RequestStatus.REJECTED);
                foodRequestRepository.save(savedRequest);
                throw new RuntimeException("Payment failed");
            }

            // 6. Update request with payment details
            savedRequest.setPaymentSuccessful(true);
            savedRequest.setPaymentId(paymentSuccess.getPaymentId());
            savedRequest.setTransactionId(paymentSuccess.getTransactionId());
            foodRequestRepository.save(savedRequest);

            // 7. Publish events using Kafka transactions for atomicity
            kafkaTemplate.executeInTransaction(operations -> {
                // Payment event
                PaymentEvent payEvt = new PaymentEvent(
                        paymentSuccess.getPaymentId(),
                        savedRequest.getId(),
                        true,
                        paymentSuccess.getCreatedAt()
                );
                operations.send("payment-events", payEvt);
                log.info("Published PaymentEvent for request: {}", savedRequest.getId());

                // Food request event for inventory processing
                FoodRequestEvent foodRequestEvent = new FoodRequestEvent(
                        savedRequest.getId(),
                        savedRequest.getFoodPostId(),
                        savedRequest.getRequestedUserId(),
                        savedRequest.getCookId(),
                        savedRequest.getQuantity(),
                        foodPost.getQuantity(), // Current available quantity
                        savedRequest.getStatus().toString(),
                        savedRequest.getCreatedAt(),
                        authHeader
                );
                operations.send("food-request-events", foodRequestEvent);
                log.info("Published FoodRequestEvent for request: {}", savedRequest.getId());

                return true;
            });

            log.info("Successfully created food request: {}", savedRequest.getId());
            return FoodDtoConverterUtil.convertToFoodRequestResponseDto(savedRequest);

        } catch (Exception e) {
            log.error("Error creating food request: {}", dto, e);
            throw e;
        }
    }

    private void updateQuantity(Long foodPostId, FoodPostResponse foodPost, Integer quantity, String authHeader) {
        FoodPostRequest quantityUpdateReq = getFoodPostRequest(foodPost, quantity);

        FoodPostResponse quantityUpdate = webClientBuilder.build()
                .put()
                .uri(Table2tableServiceURLs.UPDATE_QUANTITY_BY_ID + "{id}", foodPostId)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .bodyValue(quantityUpdateReq) // Pass the FoodPostRequest object
                .retrieve()
                .bodyToMono(FoodPostResponse.class)
                .block();
    }

    private static FoodPostRequest getFoodPostRequest(FoodPostResponse foodPost, Integer quantity) {
        FoodPostRequest quantityUpdateReq = new FoodPostRequest();
        quantityUpdateReq.setDescription(foodPost.getDescription());
        quantityUpdateReq.setPrice(foodPost.getPrice());
        quantityUpdateReq.setTitle(foodPost.getTitle());
        quantityUpdateReq.setExpiresAt(foodPost.getExpiresAt());
        quantityUpdateReq.setStatus(foodPost.getStatus());
        quantityUpdateReq.setImageUrl(foodPost.getImageUrl());
        quantityUpdateReq.setQuantity(foodPost.getQuantity() + quantity);
        return quantityUpdateReq;
    }

    @Override
    public List<FoodRequestResponseDto> getRequestsForCook(String cookEmail, String authHeader) {

        UserResponseDto user =  webClientBuilder.build()
                .get()
                .uri(Table2tableServiceURLs.GET_USER_BY_EMAIL + "{email}", cookEmail)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .bodyToMono(UserResponseDto.class)
                .block();

        assert user != null;
        List<FoodRequest> requests = foodRequestRepository.findByCookId(user.getId());
        return requests.stream()
                .map(FoodDtoConverterUtil::convertToFoodRequestResponseDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public FoodRequestResponseDto updateRequestStatus(
            Long requestId,
            RequestStatus status,
            String rejectionReason,
            String authHeader) {

        // 1. Authenticate cook
        String token = authHeader.substring(7);
        String email = jwtService.extractUsername(token);
        UserResponseDto user = webClientBuilder.build()
                .get()
                .uri(Table2tableServiceURLs.GET_USER_BY_EMAIL + "{email}", email)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .bodyToMono(UserResponseDto.class)
                .block();

        if (user == null) {
            throw new RuntimeException("User not found");
        }

        // 2. Load existing request
        FoodRequest foodRequest = foodRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));

        if (!foodRequest.getCookId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized");
        }

        // 3. Handle rejection or refund initiated
        if (status == RequestStatus.REJECTED || status == RequestStatus.REFUND_INITIATED) {
            foodRequest.setStatus(RequestStatus.REFUND_INITIATED);
            foodRequest.setRejectionReason(
                    rejectionReason != null ? rejectionReason : "No reason provided"
            );

            // 3a. Simulate refund
            PaymentRequestDto paymentRequestDto = new PaymentRequestDto();
            paymentRequestDto.setFoodRequestId(foodRequest.getId());
            paymentRequestDto.setPaymentId(foodRequest.getPaymentId());
            paymentRequestDto.setAmount(BigDecimal.valueOf(foodRequest.getPrice()));

            PaymentResponseDto refundResponse = simulateRefund(paymentRequestDto, authHeader);

            // 3b. Restore inventory via Food Service
            FoodPostResponse foodPost = webClientBuilder.build()
                    .get()
                    .uri(Table2tableServiceURLs.GET_FOOD_POST_BY_ID + "{id}", foodRequest.getFoodPostId())
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .retrieve()
                    .bodyToMono(FoodPostResponse.class)
                    .block();

            if (foodPost == null) {
                throw new ResourceNotFoundException("Food post not found");
            }




            // 3c. Publish events using Kafka transactions for atomicity
            kafkaTemplate.executeInTransaction(operations -> {
                // 3d. Publish PaymentEvent for refund
                PaymentEvent refundEvent = new PaymentEvent(
                        refundResponse != null ? refundResponse.getPaymentId() : null,
                        foodRequest.getId(),
                        false,
                        refundResponse != null
                                ? refundResponse.getCreatedAt()
                                : LocalDateTime.now()
                );

                operations.send("payment-events", refundEvent);
                log.info("Published refundEvent for request: {}", foodRequest.getId());

                // 3e. Publish FoodRequestEvent for inventory Restore
                FoodRequestEvent inventoryRestoreEvent = new FoodRequestEvent(
                        foodRequest.getId(),
                        foodRequest.getFoodPostId(),
                        foodRequest.getRequestedUserId(),
                        foodRequest.getCookId(),
                        foodRequest.getQuantity(),
                        foodPost.getQuantity(),   // current available before restore
                        foodRequest.getStatus().toString(),
                        foodRequest.getCreatedAt(),
                        authHeader
                );
                operations.send("food-inventory-restore-events", inventoryRestoreEvent);
                log.info("Published inventoryRestoreEvent for request: {}", foodRequest.getId());

                return true;
            });
        }
        // 4. Handle acceptance
        else if (status == RequestStatus.ACCEPTED) {
            foodRequest.setStatus(RequestStatus.ACCEPTED);
        }

        // 5. Persist updated request
        foodRequestRepository.save(foodRequest);

        // 7. Return DTO
        return FoodDtoConverterUtil.convertToFoodRequestResponseDto(foodRequest);
    }


    // 🔁 MOCK PAYMENT FUNCTION
    private PaymentResponseDto simulatePayment(PaymentRequestDto paymentRequestDto, String authHeader) {

        PaymentResponseDto paymentResponse = webClientBuilder.build()
                .post()
                .uri(Table2tableServiceURLs.PAY) // URL of /pay endpoint
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .bodyValue(paymentRequestDto) // Pass PaymentRequestDto as request body
                .retrieve()
                .bodyToMono(PaymentResponseDto.class)
                .block();

        System.out.println("Processing mock payment of ₹" + paymentResponse + " ...");
        return paymentResponse; // always succeed for mock
    }

    // 🔁 MOCK REFUND FUNCTION
    private PaymentResponseDto simulateRefund(PaymentRequestDto paymentRequestDto, String authHeader) {
        PaymentResponseDto paymentResponse = webClientBuilder.build()
                .post()
                .uri(Table2tableServiceURLs.REFUND) // URL of /pay endpoint
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .bodyValue(paymentRequestDto) // Pass PaymentRequestDto as request body
                .retrieve()
                .bodyToMono(PaymentResponseDto.class)
                .block();
        System.out.println("Processing mock refund of ₹" + paymentResponse + " ...");
        return null;
    }
}
