package com.dws.challenge.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

public record TransferRequest(@NotBlank(message = "Source account ID cannot be empty.") String accountFrom,
                              @NotBlank(message = "Destination account ID cannot be empty.") String accountTo,
                              @NotNull(message = "Transfer Amount cannot be null.") @Positive(message = "Transfer Amount must be positive.") BigDecimal transferAmount) implements Serializable {

    @Serial
    private static final long serialVersionUID = 2285597680730305298L;

    @JsonCreator
    public TransferRequest(@JsonProperty("accountFrom") String accountFrom, @JsonProperty("accountTo") String accountTo, @JsonProperty("transferAmount") BigDecimal transferAmount) {
        this.accountFrom = accountFrom;
        this.accountTo = accountTo;
        this.transferAmount = transferAmount;
    }
}
