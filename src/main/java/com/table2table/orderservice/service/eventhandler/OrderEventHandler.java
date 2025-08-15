package com.table2table.orderservice.service.eventhandler;

import com.table2table.orderservice.dto.enums.RequestStatus;
import com.table2table.orderservice.service.FoodRequestService;
import com.table2table.security.events.OrderStatusEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderEventHandler {

    @Autowired
    private FoodRequestService orderService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Enhanced order status event handler with error handling and manual acknowledgment
     * Processes order status updates from various services
     */
    @KafkaListener(topics = "order-status-events", groupId = "order-service")
    public void onOrderStatusUpdate(
            @Payload OrderStatusEvent evt,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        try {
            log.info("Processing OrderStatusEvent: requestId={}, status={}, partition={}, offset={}",
                    evt.getFoodRequestId(), evt.getStatus(), partition, offset);

            // Validate event data
            if (evt.getFoodRequestId() == null || evt.getStatus() == null) {
                log.error("Invalid OrderStatusEvent: requestId or status is null - {}", evt);
                sendToDlq("order-status-events-dlq", evt, "Invalid event data");
                acknowledgment.acknowledge();
                return;
            }

            // Convert status safely
            RequestStatus status;
            try {
                status = RequestStatus.valueOf(evt.getStatus());
            } catch (IllegalArgumentException e) {
                log.error("Invalid status '{}' in OrderStatusEvent: {}", evt.getStatus(), evt, e);
                sendToDlq("order-status-events-dlq", evt, "Invalid status: " + evt.getStatus());
                acknowledgment.acknowledge();
                return;
            }

            // Process the order status update
            orderService.updateRequestStatus(
                    evt.getFoodRequestId(),
                    status,
                    evt.getRejectionReason(),
                    evt.getAuthHeader()
            );

            log.info("Successfully processed OrderStatusEvent for request: {}", evt.getFoodRequestId());

            // Manual acknowledgment after successful processing
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Error processing OrderStatusEvent: {}", evt, e);

            // Send to DLQ for manual investigation
            sendToDlq("order-status-events-dlq", evt, "Processing error: " + e.getMessage());

            // Acknowledge to prevent reprocessing (since we sent to DLQ)
            acknowledgment.acknowledge();
        }
    }

    /**
     * Dead Letter Queue handler for failed order status events
     */
    @KafkaListener(topics = "order-status-events-dlq", groupId = "order-service-dlq")
    public void handleOrderStatusDlq(
            @Payload OrderStatusEvent evt,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.warn("Received failed OrderStatusEvent in DLQ: requestId={}, status={}, partition={}, offset={}",
                evt.getFoodRequestId(), evt.getStatus(), partition, offset);

        // Log for manual intervention or implement retry logic// In production, you might want to send alerts or store in a database
        acknowledgment.acknowledge();
    }

    /**
     * Utility method to send failed messages to Dead Letter Queue
     */
    private void sendToDlq(String dlqTopic, OrderStatusEvent event, String reason) {
        try {
            // Add error metadata
            event.setRejectionReason(reason + " | Original reason: " + event.getRejectionReason());
            kafkaTemplate.send(dlqTopic, event);
            log.info("Sent OrderStatusEvent to DLQ: topic={}, requestId={}, reason={}",
                    dlqTopic, event.getFoodRequestId(), reason);
        } catch (Exception e) {
            log.error("Failed to send OrderStatusEvent to DLQ: topic={}, event={}", dlqTopic, event, e);
        }
    }
}