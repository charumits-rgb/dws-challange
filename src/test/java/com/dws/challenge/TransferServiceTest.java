package com.dws.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.dws.challenge.domain.Account;
import com.dws.challenge.domain.TransferRequest;
import com.dws.challenge.exception.InsufficientBalanceException;
import com.dws.challenge.exception.InvalidAccountException;
import com.dws.challenge.exception.TransferException;
import com.dws.challenge.service.AccountsService;
import com.dws.challenge.service.NotificationService;
import com.dws.challenge.service.TransferService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest
class TransferServiceTest {

    @MockitoBean
    private NotificationService notificationService;

    @Autowired
    private AccountsService accountsService;

    @Autowired
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        accountsService.getAccountsRepository().clearAccounts();
    }

    private Account createAccount(String id, String balance) {
        Account account = new Account(id, new BigDecimal(balance));
        accountsService.createAccount(account);
        return account;
    }

    private static TransferRequest request(String from, String to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount));
    }

    @Test
    void transfer_movesMoneyAndNotifiesBothAccountHolders() {
        Account from = createAccount("Id-1", "100");
        Account to = createAccount("Id-2", "50");

        transferService.transfer(request("Id-1", "Id-2", "40"));

        assertThat(from.getBalance()).isEqualByComparingTo("60");
        assertThat(to.getBalance()).isEqualByComparingTo("90");
        // each holder is told the id of the OTHER account and the amount
        verify(notificationService).notifyAboutTransfer(eq(from), argThat(msg -> msg.contains("Id-2") && msg.contains("40")));
        verify(notificationService).notifyAboutTransfer(eq(to), argThat(msg -> msg.contains("Id-1") && msg.contains("40")));
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void transfer_wholeBalanceIsAllowed() {
        Account from = createAccount("Id-1", "100");
        createAccount("Id-2", "0");

        transferService.transfer(request("Id-1", "Id-2", "100"));

        assertThat(from.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void transfer_insufficientFunds_throwsInsufficientBalanceAndChangesNothing() {
        Account from = createAccount("Id-1", "100");
        Account to = createAccount("Id-2", "50");

        assertThatThrownBy(() -> transferService.transfer(request("Id-1", "Id-2", "100.01")))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(from.getBalance()).isEqualByComparingTo("100");
        assertThat(to.getBalance()).isEqualByComparingTo("50");
        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void transfer_nonPositiveAmount_throwsTransferException(String amount) {
        Account from = createAccount("Id-1", "100");
        createAccount("Id-2", "50");

        assertThatThrownBy(() -> transferService.transfer(request("Id-1", "Id-2", amount)))
                .isInstanceOf(TransferException.class);

        assertThat(from.getBalance()).isEqualByComparingTo("100");
        verifyNoInteractions(notificationService);
    }

    @Test
    void transfer_sameAccount_throwsTransferException() {
        Account account = createAccount("Id-1", "100");

        assertThatThrownBy(() -> transferService.transfer(request("Id-1", "Id-1", "10")))
                .isInstanceOf(TransferException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100");
        verifyNoInteractions(notificationService);
    }

    @Test
    void transfer_unknownSourceAccount_throwsInvalidAccount() {
        Account to = createAccount("Id-2", "50");

        assertThatThrownBy(() -> transferService.transfer(request("Id-1", "Id-2", "10")))
                .isInstanceOf(InvalidAccountException.class)
                .hasMessageContaining("Id-1");

        assertThat(to.getBalance()).isEqualByComparingTo("50");
        verifyNoInteractions(notificationService);
    }

    @Test
    void transfer_unknownDestinationAccount_throwsInvalidAccount() {
        Account from = createAccount("Id-1", "100");

        assertThatThrownBy(() -> transferService.transfer(request("Id-1", "Id-2", "10")))
                .isInstanceOf(InvalidAccountException.class)
                .hasMessageContaining("Id-2");

        assertThat(from.getBalance()).isEqualByComparingTo("100");
        verifyNoInteractions(notificationService);
    }

    @Test
    void transfer_stillSucceedsWhenNotificationFails() {
        Account from = createAccount("Id-1", "100");
        Account to = createAccount("Id-2", "0");
        doThrow(new IllegalStateException("mail server down"))
                .when(notificationService).notifyAboutTransfer(any(Account.class), anyString());

        transferService.transfer(request("Id-1", "Id-2", "10"));

        assertThat(from.getBalance()).isEqualByComparingTo("90");
        assertThat(to.getBalance()).isEqualByComparingTo("10");
        verify(notificationService, times(2)).notifyAboutTransfer(any(Account.class), anyString());
    }

    @Test
    void concurrentTransfersInOppositeDirections_neverDeadlockAndConserveMoney() {
        Account a = createAccount("A", "10000");
        Account b = createAccount("B", "10000");

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                runConcurrently(16, 500, i -> {
                    // even threads send A->B, odd threads send B->A: the classic deadlock set-up
                    if (i % 2 == 0) {
                        transferService.transfer(request("A", "B", "1"));
                    } else {
                        transferService.transfer(request("B", "A", "1"));
                    }
                }));

        // equal traffic in each direction, so both accounts end where they started
        assertThat(a.getBalance()).isEqualByComparingTo("10000");
        assertThat(b.getBalance()).isEqualByComparingTo("10000");
    }

    @Test
    void concurrentTransfersOutOfOneAccount_neverOverdrawAndNoTransferIsLost() {
        Account from = createAccount("A", "100");
        Account to = createAccount("B", "0");
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // 20 threads x 10 attempts = 200 attempts to move 1 unit out of an account holding 100.
        // Exactly 100 must succeed; the rest must be rejected for insufficient funds (never for lock failures).
        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                runConcurrently(20, 10, i -> {
                    try {
                        transferService.transfer(request("A", "B", "1"));
                        succeeded.incrementAndGet();
                    } catch (InsufficientBalanceException ex) {
                        rejected.incrementAndGet();
                    }
                }));

        assertThat(succeeded.get()).isEqualTo(100);
        assertThat(rejected.get()).isEqualTo(100);
        assertThat(from.getBalance()).isEqualByComparingTo("0");
        assertThat(to.getBalance()).isEqualByComparingTo("100");
        verify(notificationService, times(200)).notifyAboutTransfer(any(Account.class), anyString());
    }

    @Test
    void concurrentTransfersAcrossManyAccounts_conserveTotalMoneyAndNeverGoNegative() {
        int accountCount = 10;
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            accounts.add(createAccount("Id-" + i, "1000"));
        }

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                runConcurrently(16, 500, threadIndex -> {
                    int from = ThreadLocalRandom.current().nextInt(accountCount);
                    int to = (from + 1 + ThreadLocalRandom.current().nextInt(accountCount - 1)) % accountCount;
                    try {
                        transferService.transfer(request("Id-" + from, "Id-" + to, "10"));
                    } catch (InsufficientBalanceException ignored) {
                        // expected now and then under random load
                    }
                }));

        BigDecimal total = accounts.stream().map(Account::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("10000");
        assertThat(accounts).allSatisfy(acc -> assertThat(acc.getBalance().signum()).isGreaterThanOrEqualTo(0));
    }

    /** Starts all threads at the same moment, each running {@code action} {@code repeats} times. */
    private static void runConcurrently(int threads, int repeats, IntConsumer action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int r = 0; r < repeats; r++) {
                        action.accept(threadIndex);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(); // rethrows any unexpected exception from a worker thread
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

