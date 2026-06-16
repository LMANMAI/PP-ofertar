package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.TicketItemResponse;
import ar.edu.ofertAR.dto.response.TicketResponse;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketService {

    private final TicketRepository ticketRepository;

    @Value("${ticket.upload-dir:uploads/tickets}")
    private String uploadDir;

    /**
     * Recibe la imagen del ticket, la guarda en disco y crea el registro en DB.
     * Por ahora queda en status PENDING — el OCR se integra después.
     */
    public TicketResponse scan(MultipartFile file, User user) {
        validateImage(file);

        String savedPath = saveImage(file);

        Ticket ticket = Ticket.builder()
                .user(user)
                .imagePath(savedPath)
                .status(TicketStatus.PENDING)
                .build();

        ticket = ticketRepository.save(ticket);

        log.info("Ticket {} creado para usuario {} — pendiente de OCR", ticket.getId(), user.getEmail());

        return toResponse(ticket);
    }

    private void validateImage(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Solo se aceptan imágenes (jpg, png, etc.)");
        }
    }

    private String saveImage(MultipartFile file) {
        try {
            Path uploadPath = Paths.get(uploadDir);
            Files.createDirectories(uploadPath);

            String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = uploadPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Error al guardar la imagen del ticket", e);
        }
    }

    private TicketResponse toResponse(Ticket ticket) {
        return TicketResponse.builder()
                .id(ticket.getId())
                .storeName(ticket.getStoreName())
                .total(ticket.getTotal())
                .status(ticket.getStatus())
                .createdAt(ticket.getCreatedAt())
                .items(ticket.getItems().stream()
                        .map(item -> TicketItemResponse.builder()
                                .id(item.getId())
                                .description(item.getDescription())
                                .quantity(item.getQuantity())
                                .unitPrice(item.getUnitPrice())
                                .subtotal(item.getSubtotal())
                                .barcode(item.getBarcode())
                                .build())
                        .toList())
                .build();
    }
}
