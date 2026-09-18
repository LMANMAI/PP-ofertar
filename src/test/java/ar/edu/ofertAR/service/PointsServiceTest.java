package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.RedeemRequest;
import ar.edu.ofertAR.model.PointsReason;
import ar.edu.ofertAR.model.PointsTransaction;
import ar.edu.ofertAR.model.Referral;
import ar.edu.ofertAR.model.ReferralStatus;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.PointsTransactionRepository;
import ar.edu.ofertAR.repository.ReferralRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ReferralRepository referralRepository;
    @Mock private PointsTransactionRepository pointsTransactionRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private PointsService pointsService;

    @BeforeEach
    void setUp() {
        pointsService = new PointsService(
                userRepository, referralRepository, pointsTransactionRepository, ticketRepository, transactionTemplate);
    }

    private static User user(Long id, String referralCode, int points) {
        User u = User.builder().name("Test").email(id + "@test.com").password("x")
                .referralCode(referralCode).build();
        u.setId(id);
        u.setPoints(points);
        return u;
    }

    @Nested
    @DisplayName("acreditacion al registrarse con codigo de invitacion")
    class ReferralSignup {

        @Test
        @DisplayName("codigo valido: crea el Referral y acredita al referido")
        void validCode_createsReferralAndCreditsSignup() {
            User referrer = user(1L, "REFERRER", 0);
            User referred = user(2L, "REFERRED", 0);
            when(userRepository.findByReferralCode("REFERRER")).thenReturn(Optional.of(referrer));

            pointsService.applyReferralSignup(referred, "REFERRER");

            ArgumentCaptor<Referral> referralCaptor = ArgumentCaptor.forClass(Referral.class);
            verify(referralRepository).save(referralCaptor.capture());
            Referral saved = referralCaptor.getValue();
            assertEquals(referrer, saved.getReferrer());
            assertEquals(referred, saved.getReferred());
            assertEquals(ReferralStatus.PENDING, saved.getStatus());

            assertEquals(20, referred.getPoints());
            verify(userRepository).save(referred);
            ArgumentCaptor<PointsTransaction> txCaptor = ArgumentCaptor.forClass(PointsTransaction.class);
            verify(pointsTransactionRepository).save(txCaptor.capture());
            assertEquals(PointsReason.REFERRAL_SIGNUP, txCaptor.getValue().getReason());
            assertEquals(20, txCaptor.getValue().getPoints());
        }

        @Test
        @DisplayName("codigo inexistente: no bloquea el alta ni acredita nada")
        void unknownCode_isIgnoredSilently() {
            User referred = user(2L, "REFERRED", 0);
            when(userRepository.findByReferralCode("NOEXISTE")).thenReturn(Optional.empty());

            assertDoesNotThrow(() -> pointsService.applyReferralSignup(referred, "NOEXISTE"));

            assertEquals(0, referred.getPoints());
            verifyNoInteractions(referralRepository);
            verifyNoInteractions(pointsTransactionRepository);
        }

        @Test
        @DisplayName("codigo propio: no se auto-refiere ni acredita")
        void ownCode_isIgnoredSilently() {
            User referred = user(2L, "REFERRED", 0);
            when(userRepository.findByReferralCode("REFERRED")).thenReturn(Optional.of(referred));

            pointsService.applyReferralSignup(referred, "REFERRED");

            assertEquals(0, referred.getPoints());
            verifyNoInteractions(referralRepository);
            verifyNoInteractions(pointsTransactionRepository);
        }

        @Test
        @DisplayName("sin codigo: no hace nada")
        void blankCode_isIgnored() {
            User referred = user(2L, "REFERRED", 0);

            pointsService.applyReferralSignup(referred, null);
            pointsService.applyReferralSignup(referred, "  ");

            verifyNoInteractions(userRepository);
            verifyNoInteractions(referralRepository);
        }
    }

    @Nested
    @DisplayName("activacion del referido al primer ticket")
    class ReferralActivation {

        private Ticket firstTicket(User owner) {
            Ticket t = Ticket.builder().user(owner).status(TicketStatus.PROCESSED).build();
            t.setId(99L);
            return t;
        }

        @Test
        @DisplayName("primer ticket con referral PENDING y bajo el tope: acredita al referrer")
        void firstTicket_underCap_creditsReferrer() {
            User referrer = user(1L, "REFERRER", 0);
            User referred = user(2L, "REFERRED", 0);
            Referral referral = Referral.builder().referrer(referrer).referred(referred)
                    .status(ReferralStatus.PENDING).build();

            when(ticketRepository.countByUserIdAndStatus(2L, TicketStatus.PROCESSED)).thenReturn(1L);
            when(referralRepository.findByReferredIdAndStatus(2L, ReferralStatus.PENDING))
                    .thenReturn(Optional.of(referral));
            when(referralRepository.countByReferrerIdAndActivatedAtGreaterThanEqualAndActivatedAtLessThan(
                    eq(1L), any(), any())).thenReturn(3L);

            pointsService.onTicketProcessed(firstTicket(referred));

            assertEquals(50, referrer.getPoints());
            assertEquals(ReferralStatus.ACTIVATED, referral.getStatus());
            assertNotNull(referral.getActivatedAt());
            verify(referralRepository).save(referral);
        }

        @Test
        @DisplayName("tope mensual alcanzado: marca ACTIVATED pero no acredita puntos")
        void firstTicket_atMonthlyCap_marksActivatedWithoutPoints() {
            User referrer = user(1L, "REFERRER", 100);
            User referred = user(2L, "REFERRED", 0);
            Referral referral = Referral.builder().referrer(referrer).referred(referred)
                    .status(ReferralStatus.PENDING).build();

            when(ticketRepository.countByUserIdAndStatus(2L, TicketStatus.PROCESSED)).thenReturn(1L);
            when(referralRepository.findByReferredIdAndStatus(2L, ReferralStatus.PENDING))
                    .thenReturn(Optional.of(referral));
            when(referralRepository.countByReferrerIdAndActivatedAtGreaterThanEqualAndActivatedAtLessThan(
                    eq(1L), any(), any())).thenReturn(15L);

            pointsService.onTicketProcessed(firstTicket(referred));

            assertEquals(100, referrer.getPoints(), "no se acreditan puntos por encima del tope");
            assertEquals(ReferralStatus.ACTIVATED, referral.getStatus(), "igual se marca activado");
            assertNotNull(referral.getActivatedAt());
            verifyNoInteractions(pointsTransactionRepository);
        }

        @Test
        @DisplayName("no es el primer ticket del usuario: no evalua ningun referral")
        void notFirstTicket_doesNothing() {
            User referred = user(2L, "REFERRED", 0);
            when(ticketRepository.countByUserIdAndStatus(2L, TicketStatus.PROCESSED)).thenReturn(2L);

            pointsService.onTicketProcessed(firstTicket(referred));

            verifyNoInteractions(referralRepository);
        }

        @Test
        @DisplayName("primer ticket pero sin referral PENDING: no hace nada")
        void firstTicket_noPendingReferral_doesNothing() {
            User referred = user(2L, "REFERRED", 0);
            when(ticketRepository.countByUserIdAndStatus(2L, TicketStatus.PROCESSED)).thenReturn(1L);
            when(referralRepository.findByReferredIdAndStatus(2L, ReferralStatus.PENDING))
                    .thenReturn(Optional.empty());

            pointsService.onTicketProcessed(firstTicket(referred));

            verifyNoInteractions(pointsTransactionRepository);
        }
    }

    @Nested
    @DisplayName("canje de recompensas")
    class Redeem {

        @Test
        @DisplayName("saldo insuficiente: 409 y no descuenta nada")
        void insufficientBalance_throwsConflict() {
            User u = user(1L, "CODE1", 50);
            RedeemRequest request = new RedeemRequest("mini-descuento", 100);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> pointsService.redeem(u, request));

            assertEquals(409, ex.getStatusCode().value());
            assertEquals(50, u.getPoints());
            verifyNoInteractions(pointsTransactionRepository);
        }

        @Test
        @DisplayName("rewardId inexistente: 400")
        void unknownRewardId_throwsBadRequest() {
            User u = user(1L, "CODE1", 1000);
            RedeemRequest request = new RedeemRequest("no-existe", 100);

            assertThrows(IllegalArgumentException.class, () -> pointsService.redeem(u, request));
            assertEquals(1000, u.getPoints());
            verifyNoInteractions(pointsTransactionRepository);
        }

        @Test
        @DisplayName("saldo suficiente: descuenta el costo y registra el canje")
        void sufficientBalance_debitsAndLogs() {
            User u = user(1L, "CODE1", 200);
            RedeemRequest request = new RedeemRequest("mini-descuento", 100);

            var response = pointsService.redeem(u, request);

            assertEquals(100, u.getPoints());
            assertEquals(100, response.getBalance());
            assertEquals("CODE1", response.getReferralCode());

            ArgumentCaptor<PointsTransaction> txCaptor = ArgumentCaptor.forClass(PointsTransaction.class);
            verify(pointsTransactionRepository).save(txCaptor.capture());
            assertEquals(PointsReason.REDEEM, txCaptor.getValue().getReason());
            assertEquals(-100, txCaptor.getValue().getPoints());
        }
    }
}
