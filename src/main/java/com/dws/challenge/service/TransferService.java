package com.dws.challenge.service;

import com.dws.challenge.domain.Account;
import com.dws.challenge.domain.TransferRequest;
import com.dws.challenge.exception.InsufficientBalanceException;
import com.dws.challenge.exception.InvalidAccountException;
import com.dws.challenge.exception.TransferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Handles money transfers between two accounts.
 */
@Service
@Slf4j
public class TransferService {

    private final AccountsService accountsService;
    private final NotificationService notificationService;

    public TransferService(AccountsService accountsService, NotificationService notificationService) {
        this.accountsService = accountsService;
        this.notificationService = notificationService;
    }

    /**
     * Moves the requested amount from the source to the destination account and
     * notifies both account holders.
     */
    public void transfer(TransferRequest request) throws TransferException, InvalidAccountException, InsufficientBalanceException {
        validateTransferRequest(request);

        Account sourceAccount = fetchAccountDetail(request.accountFrom());
        Account destinationAccount = fetchAccountDetail(request.accountTo());
        BigDecimal transferAmount = request.transferAmount();

        performTransaction(sourceAccount, destinationAccount, transferAmount);

        //Notify Source user
        notifyUser(sourceAccount, String.format("Amount %s has been successfully transferred to %s", transferAmount, destinationAccount.getAccountId()));
        //Notify Destination user
        notifyUser(destinationAccount, String.format("Amount %s has been received from %s", transferAmount, sourceAccount.getAccountId()));
    }

    private void notifyUser(Account accountHolder, String message) {
        try {
            notificationService.notifyAboutTransfer(accountHolder, message);
        } catch (RuntimeException ex) {
            log.error("Failed to send transfer notification to account {}", accountHolder.getAccountId(), ex);
        }
    }

    /**
     * Checks the balance and updates both accounts as one step, so no other
     * transfer can see or change them half-way through.
     */
    private void performTransaction(Account sourceAccount, Account destinationAccount, BigDecimal transferAmount) {

        // Always acquire locks in the same order, regardless of transfer direction, to avoid deadlocks.
        Account[] accountsToLock = {sourceAccount, destinationAccount};
        Arrays.sort(accountsToLock, Comparator.comparing(Account::getAccountId));

        synchronized (accountsToLock[0]) {
            synchronized (accountsToLock[1]) {
                if (sourceAccount.getBalance().compareTo(transferAmount) < 0) {
                    throw new InsufficientBalanceException(String.format(
                            "Insufficient balance, attempted to withdraw %s, but balance is %s",
                            transferAmount, sourceAccount.getBalance()));
                }
                sourceAccount.setBalance(sourceAccount.getBalance().subtract(transferAmount));
                destinationAccount.setBalance(destinationAccount.getBalance().add(transferAmount));
            }
        }
    }

    private void validateTransferRequest(TransferRequest request) {
        if (request.transferAmount().signum() <= 0) {
            throw new TransferException("Transfer amount must be positive.");
        }
        if (request.accountFrom().equals(request.accountTo())) {
            throw new TransferException("Source and destination accounts must be different.");
        }
    }

    private Account fetchAccountDetail(String accountId) throws InvalidAccountException {
        Account account = accountsService.getAccount(accountId);
        if (account == null) {
            log.warn("Account with id {} not found", accountId);
            throw new InvalidAccountException(String.format("Account not found for id %s", accountId));
        }
        return account;
    }

}
