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
<<<<<<< HEAD
=======
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
>>>>>>> 37cf6df (Merge pull request #23 from LMANMAI/auditoria-tecnica)
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
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

<<<<<<< HEAD
        log.info("Ticket {} creado para usuario {} — pendiente de OCR", ticket.getId(), user.getEmail());
=======
        // Read every payload up front: MultipartFile is tied to the request
        // thread, so the bytes have to be pulled before handing the work off.
        List<TicketProcessingService.PagePayload> pages = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                pages.add(new TicketProcessingService.PagePayload(file.getBytes(), detectType(file).mimeType()));
            } catch (IOException e) {
                log.error("Error al leer archivo del ticket {}: {}", ticket.getId(), e.getMessage());
                ticket.setStatus(TicketStatus.FAILED);
                ticketRepository.save(ticket);
                return toResponse(ticket);
            }
        }
>>>>>>> 37cf6df (Merge pull request #23 from LMANMAI/auditoria-tecnica)

        return toResponse(ticket);
    }

    public List<TicketResponse> getTicketsByUser(User user) {
        return ticketRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

<<<<<<< HEAD
=======
    public TicketResponse getTicketById(Long id, User user) {
        Ticket ticket = ticketRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Ticket no encontrado"));
        return toResponse(ticket);
    }

    @Transactional
    public TicketResponse updateTicket(Long id, UpdateTicketRequest request, User user) {
        Ticket ticket = ticketRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Ticket no encontrado"));

        // Corrections are only meaningful on a finished ticket, and only on
        // the first pass: once confirmed, the figures feed the savings history
        // and recurring-product stats, so they stop being editable.
        if (ticket.getStatus() != TicketStatus.PROCESSED) {
            throw new IllegalArgumentException("El ticket todavía se está procesando");
        }
        if (ticket.isReviewed()) {
            throw new IllegalArgumentException("Este ticket ya fue confirmado y no puede modificarse");
        }

        if (request.getStoreName() != null) {
            ticket.setStoreName(request.getStoreName());
        }

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            // What the lines added up to before this edit. The stored subtotal
            // is the one printed on the receipt, which the item sum does not
            // reproduce while the OCR reports some lines gross and others net;
            // moving it by the size of the correction keeps the printed figure
            // as the baseline instead of replacing it with the item sum.
            BigDecimal grossBefore = ticket.getItems().stream()
                    .map(it -> it.getSubtotal() != null ? it.getSubtotal() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Set<Long> requestedIds = request.getItems().stream()
                    .map(UpdateTicketRequest.TicketItemUpdate::getId)
                    .filter(rid -> rid != null)
                    .collect(Collectors.toSet());

            Iterator<TicketItem> iterator = ticket.getItems().iterator();
            while (iterator.hasNext()) {
                TicketItem existing = iterator.next();
                if (!requestedIds.contains(existing.getId())) {
                    iterator.remove();
                }
            }

            BigDecimal newSubtotal = BigDecimal.ZERO;
            for (UpdateTicketRequest.TicketItemUpdate reqItem : request.getItems()) {
                TicketItem item;
                if (reqItem.getId() != null) {
                    item = ticket.getItems().stream()
                            .filter(i -> i.getId().equals(reqItem.getId()))
                            .findFirst()
                            .orElse(null);
                    if (item != null) {
                        if (reqItem.getDescription() != null) {
                            item.setDescription(reqItem.getDescription());
                        }
                        if (reqItem.getQuantity() != null) {
                            item.setQuantity(reqItem.getQuantity());
                        }
						if (reqItem.getUnitPrice() != null) {
							item.setUnitPrice(reqItem.getUnitPrice());
						}
						if (reqItem.getOriginalPrice() != null) {
							item.setOriginalPrice(reqItem.getOriginalPrice());
						}
						if (reqItem.getDiscountAmount() != null) {
							item.setDiscountAmount(reqItem.getDiscountAmount());
						}
                        item.setSubtotal(
                                item.getUnitPrice().multiply(item.getQuantity())
                                        .setScale(2, RoundingMode.HALF_UP)
                        );
                        newSubtotal = newSubtotal.add(item.getSubtotal());
                        continue;
                    }
                }

                BigDecimal qty = reqItem.getQuantity() != null && reqItem.getQuantity().signum() > 0
                        ? reqItem.getQuantity()
                        : BigDecimal.ONE;
                BigDecimal price = reqItem.getUnitPrice() != null
                        ? reqItem.getUnitPrice()
                        : BigDecimal.ZERO;
				item = TicketItem.builder()
						.ticket(ticket)
						.description(reqItem.getDescription() != null
								? reqItem.getDescription() : "")
						.quantity(qty)
						.unitPrice(price)
						.originalPrice(reqItem.getOriginalPrice())
						.discountAmount(reqItem.getDiscountAmount())
						.subtotal(price.multiply(qty).setScale(2, RoundingMode.HALF_UP))
						.build();
                ticket.getItems().add(item);
                newSubtotal = newSubtotal.add(item.getSubtotal());
            }

			BigDecimal previousSubtotal = ticket.getSubtotal();
			BigDecimal correctedSubtotal = previousSubtotal != null
					? previousSubtotal.add(newSubtotal.subtract(grossBefore))
					: newSubtotal;
			ticket.setSubtotal(correctedSubtotal);
			BigDecimal discounts = ticket.getTotalDiscounts() != null
					? ticket.getTotalDiscounts()
					: BigDecimal.ZERO;
			ticket.setTotal(correctedSubtotal.subtract(discounts));
        }

        // Confirming is what closes the editing window; from here the ticket
        // is read-only.
        ticket.setReviewed(true);

        ticket = ticketRepository.save(ticket);
        log.info("Ticket {} actualizado y confirmado — {} items", ticket.getId(), ticket.getItems().size());
        return toResponse(ticket);
    }

    @Transactional
    public void deleteTicket(Long id, User user) {
        Ticket ticket = ticketRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Ticket no encontrado"));
        deleteImageFile(ticket.getImagePath());
        ticketRepository.delete(ticket);
        log.info("Ticket {} eliminado por el usuario {}", id, user.getId());
    }

    private void enforceTicketLimit(User user) {
        List<Ticket> tickets = ticketRepository.findByUserIdOrderByCreatedAtAsc(user.getId());
        while (tickets.size() >= maxTicketsPerUser) {
            Ticket oldest = tickets.remove(0);
            deleteImageFile(oldest.getImagePath());
            ticketRepository.delete(oldest);
            log.info("Eliminado ticket {} (mas antiguo) del usuario {} por limite de {} tickets",
                    oldest.getId(), user.getEmail(), maxTicketsPerUser);
        }
    }

    private void deleteImageFile(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) return;
        for (String path : imagePath.split(",")) {
            String trimmed = path.trim();
            if (!trimmed.isEmpty()) {
                try {
                    Files.deleteIfExists(Path.of(trimmed));
                } catch (IOException e) {
                    log.warn("No se pudo eliminar imagen {}: {}", trimmed, e.getMessage());
                }
            }
        }
    }

>>>>>>> 37cf6df (Merge pull request #23 from LMANMAI/auditoria-tecnica)
    private void validateImage(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío");
        }

<<<<<<< HEAD
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Solo se aceptan imágenes (jpg, png, etc.)");
=======
        detectType(file);
    }

    /** El tipo se decide por los primeros bytes, no por el Content-Type que declara el cliente. */
    private FileSniffer.Detected detectType(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return FileSniffer.detect(in.readNBytes(FileSniffer.HEADER_BYTES))
                    .orElseThrow(() -> new IllegalArgumentException("Solo se aceptan imagenes (jpg, png, etc.) o PDF"));
        } catch (IOException e) {
            throw new RuntimeException("Error al leer el archivo del ticket", e);
>>>>>>> 37cf6df (Merge pull request #23 from LMANMAI/auditoria-tecnica)
        }
    }

    private String saveImage(MultipartFile file) {
        try {
            Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(uploadPath);

            // El nombre que manda el cliente se descarta: UUID + extension detectada.
            String filename = UUID.randomUUID() + "." + detectType(file).extension();
            Path filePath = uploadPath.resolve(filename).normalize();
            if (!filePath.startsWith(uploadPath)) {
                throw new IllegalArgumentException("Nombre de archivo invalido");
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

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
