package com.table2table.orderservice.service;


import com.table2table.orderservice.dto.*;
import com.table2table.orderservice.dto.enums.RequestStatus;
import com.table2table.orderservice.entity.FoodRequest;
import com.table2table.orderservice.repository.FoodRequestRepository;
import com.table2table.orderservice.util.FoodDtoConverterUtil;
import com.table2table.security.constants.Table2tableServiceURLs;
import com.table2table.security.dto.UserResponseDto;
import com.table2table.security.exceptions.InsufficientQuantityException;
import com.table2table.security.exceptions.ResourceNotFoundException;
import com.table2table.security.service.JwtService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FoodRequestServiceImpl implements FoodRequestService {

    private final FoodRequestRepository foodRequestRepository;
    private final JwtService jwtService;
    private final WebClient.Builder webClientBuilder;

    @Override
    @Transactional
    public FoodRequestResponseDto createRequest(CreateFoodRequestDto dto, String authHeader) {
        String token = authHeader.substring(7);
        String email = jwtService.extractUsername(token);

        UserResponseDto user =  webClientBuilder.build()
                .get()
                .uri(Table2tableServiceURLs.GET_USER_BY_EMAIL + "{email}", email)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .bodyToMono(UserResponseDto.class)
                .block();

        FoodPostResponse foodPost =  webClientBuilder.build()
                .get()
                .uri(Table2tableServiceURLs.GET_FOOD_POST_BY_ID + "{id}", dto.getFoodPostId())
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .bodyToMono(FoodPostResponse.class)
                .block();


        if (foodPost.getQuantity() <= 0) {
            throw new RuntimeException("Food not available");
        }

        assert user != null;
        FoodRequest foodRequest = new FoodRequest(dto.getQuantity(), RequestStatus.PENDING
                ,null,LocalDateTime.now(),false,foodPost.getId()
                ,user.getId(),LocalDateTime.now(), foodPost.getCookId(),foodPost.getTitle()
                ,user.getName(),foodPost.getPrice());


        PaymentRequestDto paymentRequestDto = new PaymentRequestDto();
        paymentRequestDto.setFoodRequestId(foodRequest.getId());
        paymentRequestDto.setAmount(BigDecimal.valueOf(foodRequest.getPrice()));
        // ✅ MOCK PAYMENT SIMULATION
        PaymentResponseDto paymentSuccess = simulatePayment(paymentRequestDto, authHeader);
        if (null == paymentSuccess) {
            throw new RuntimeException("Payment failed");
        }
        else{
            foodRequest.setPaymentSuccessful(true);
            foodRequest.setPaymentId(paymentSuccess.getPaymentId());
            foodRequest.setTransactionId(paymentSuccess.getTransactionId());
        }

        foodRequestRepository.save(foodRequest);

        if(foodRequest.getQuantity()<foodPost.getQuantity()) {
            updateQuantity(foodRequest.getFoodPostId(), foodPost, -foodRequest.getQuantity(),authHeader);
            foodRequest.setStatus(RequestStatus.PENDING);
        }
        else{
            throw new InsufficientQuantityException("requested quantity is more than what we have");
        }


        return FoodDtoConverterUtil.convertToFoodRequestResponseDto(foodRequest);
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
    public FoodRequestResponseDto updateRequestStatus(Long requestId, RequestStatus status, String rejectionReason, String authHeader) {
        String token = authHeader.substring(7);
        String email = jwtService.extractUsername(token);

        UserResponseDto user =  webClientBuilder.build()
                .get()
                .uri(Table2tableServiceURLs.GET_USER_BY_EMAIL + "{email}", email)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .bodyToMono(UserResponseDto.class)
                .block();

        FoodRequest foodRequest = foodRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));

        assert user != null;
        if (!foodRequest.getCookId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized");
        }

        if (status == RequestStatus.REJECTED || status == RequestStatus.REFUND_INITIATED) {
            foodRequest.setStatus(RequestStatus.REFUND_INITIATED);
            foodRequest.setRejectionReason(rejectionReason != null ? rejectionReason : "No reason provided");

            // Simulate refund
            PaymentRequestDto paymentRequestDto = new PaymentRequestDto();
            paymentRequestDto.setFoodRequestId(foodRequest.getId());
            paymentRequestDto.setPaymentId(foodRequest.getPaymentId());
            paymentRequestDto.setAmount(BigDecimal.valueOf(foodRequest.getPrice()));
            PaymentResponseDto paymentResponseDto = simulateRefund(paymentRequestDto, authHeader);

            // Increase the quantity back
            FoodPostResponse foodPost =  webClientBuilder.build()
                    .get()
                    .uri(Table2tableServiceURLs.GET_FOOD_POST_BY_ID + "{id}", foodRequest.getFoodPostId())
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .retrieve()
                    .bodyToMono(FoodPostResponse.class)
                    .block();

            updateQuantity(foodRequest.getFoodPostId(), foodPost, foodRequest.getQuantity(), authHeader);


        } else if (status == RequestStatus.ACCEPTED) {
            foodRequest.setStatus(RequestStatus.ACCEPTED);
        }

        foodRequestRepository.save(foodRequest);
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
