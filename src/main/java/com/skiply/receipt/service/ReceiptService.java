package com.skiply.receipt.service;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.skiply.receipt.entity.Receipt;
import com.skiply.receipt.entity.Student_Receipt_Dto;
import com.skiply.receipt.exception.ResourceNotFoundException;
import com.skiply.receipt.repository.ReceiptRepository;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import reactor.core.publisher.Mono;

@Service
public class ReceiptService {

    private static final Logger logger = LoggerFactory.getLogger(ReceiptService.class); // Logger

    @Autowired
    private ReceiptRepository transactionRepository;

    @Autowired
    private WebClient.Builder webClientBuilder; // WebClient Builder

    @Value("${student.management.service.url}")
    private String studentManagementServiceUrl;

    @CircuitBreaker(name = "studentService", fallbackMethod = "studentServiceFallback")
    public Receipt addTransaction(Receipt transaction) {
        // Call Student Management Service to check if student exists
        String studentCheckUrl = studentManagementServiceUrl + "/" + transaction.getStudentId();
        logger.info("Calling Student Management Service with URL: {}", studentCheckUrl); // Log the URL

        // Asynchronously fetch student details using WebClient
        Mono<Student_Receipt_Dto> studentMono = webClientBuilder.build()
                .get()
                .uri(studentCheckUrl)
                .retrieve()
                .bodyToMono(Student_Receipt_Dto.class);

        Student_Receipt_Dto student = studentMono.block();  // Blocking here for simplicity

        if (student != null) {
            logger.info("Student found: {}", student.getStudentId());
            // Student exists, save the transaction
            return transactionRepository.save(transaction);
        } else {
            logger.error("Student not found with ID: {}", transaction.getStudentId());
            throw new ResourceNotFoundException("Student not found with ID: " + transaction.getStudentId());
        }
    }

    @CircuitBreaker(name = "studentService", fallbackMethod = "studentServiceFallbackForGetReceipt")
    public Mono<Student_Receipt_Dto> getReceiptByStudentId(int studentId) {
        // Fetch student details
        String studentCheckUrl = studentManagementServiceUrl + "/" + studentId;
        logger.info("Fetching student details with URL: {}", studentCheckUrl);

        Mono<Student_Receipt_Dto> studentMono = webClientBuilder.build()
                .get()
                .uri(studentCheckUrl)
                .retrieve()
                .bodyToMono(Student_Receipt_Dto.class);

        // Fetch transaction details for the student
        List<Receipt> transactions = transactionRepository.findByStudentId(studentId);

        return studentMono.map(student -> {
            if (student == null) {
                logger.error("Student not found with ID: {}", studentId);
                throw new ResourceNotFoundException("Student not found with ID: " + studentId);
            }
            Student_Receipt_Dto receipt = new Student_Receipt_Dto();
            receipt.setStudentId(student.getStudentId());
            receipt.setStudentName(student.getStudentName());
            receipt.setGrade(student.getGrade());

            if (!transactions.isEmpty()) {
                Receipt latestTransaction = transactions.get(transactions.size() - 1);
                receipt.setTransactionId(latestTransaction.getTransactionId());
                receipt.setAmount(latestTransaction.getAmount());
                receipt.setTransactionDate(latestTransaction.getTransactionDate().toString());
                receipt.setCardType(latestTransaction.getCardType());
                receipt.setReferenceNumber(latestTransaction.getReferenceNumber());
                receipt.setStatus(latestTransaction.getStatus());
                logger.info("Returning receipt for student ID: {}", studentId);
            } else {
                logger.warn("No transactions found for student ID: {}", studentId);
                throw new ResourceNotFoundException("No transactions found for student ID: " + studentId);
            }
            return receipt;
        });
    }

    // Fallback method for addTransaction in case of circuit breaker open or failure
    public Receipt studentServiceFallback(Receipt transaction, Throwable t) {
        logger.error("Student service is unavailable: {}. Cause: {}", transaction.getStudentId(), t.getMessage());
        throw new ResourceNotFoundException("Student service is unavailable for ID: " + transaction.getStudentId());
    }

    // Fallback method for getReceiptByStudentId in case of circuit breaker open or failure
    public Mono<Student_Receipt_Dto> studentServiceFallbackForGetReceipt(int studentId, Throwable t) {
        logger.error("Student service is unavailable for student ID: {}. Cause: {}", studentId, t.getMessage());
        return Mono.error(new ResourceNotFoundException("Student service is unavailable. Please try again later."));
    }
}