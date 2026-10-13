package com.dws.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.math.BigDecimal;

import com.dws.challenge.domain.Account;
import com.dws.challenge.service.AccountsService;
import com.dws.challenge.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@WebAppConfiguration
class AccountsControllerTest {

  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @MockitoBean
  private NotificationService notificationService;

  @Autowired
  private WebApplicationContext webApplicationContext;

  @BeforeEach
  void prepareMockMvc() {
    this.mockMvc = webAppContextSetup(this.webApplicationContext).build();

    // Reset the existing accounts before each test.
    accountsService.getAccountsRepository().clearAccounts();
    accountsService.createAccount(new Account("Id-1", new BigDecimal("100")));
    accountsService.createAccount(new Account("Id-2", new BigDecimal("50")));
  }

  @Test
  void createAccount() throws Exception {
    this.mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
      .content("{\"accountId\":\"Id-123\",\"balance\":1000}")).andExpect(status().isCreated());

    Account account = accountsService.getAccount("Id-123");
    assertThat(account.getAccountId()).isEqualTo("Id-123");
    assertThat(account.getBalance()).isEqualByComparingTo("1000");
  }

  @Test
  void createDuplicateAccount() throws Exception {
    this.mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
      .content("{\"accountId\":\"Id-123\",\"balance\":1000}")).andExpect(status().isCreated());

    this.mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
      .content("{\"accountId\":\"Id-123\",\"balance\":1000}")).andExpect(status().isBadRequest());
  }

  @Test
  void createAccountNoAccountId() throws Exception {
    this.mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
      .content("{\"balance\":1000}")).andExpect(status().isBadRequest());
  }

  @Test
  void createAccountNoBalance() throws Exception {
    this.mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
      .content("{\"accountId\":\"Id-123\"}")).andExpect(status().isBadRequest());
  }

  @Test
  void createAccountNoBody() throws Exception {
    this.mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON))
      .andExpect(status().isBadRequest());
  }

  @Test
  void createAccountNegativeBalance() throws Exception {
    this.mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
      .content("{\"accountId\":\"Id-123\",\"balance\":-1000}")).andExpect(status().isBadRequest());
  }

  @Test
  void createAccountEmptyAccountId() throws Exception {
    this.mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
      .content("{\"accountId\":\"\",\"balance\":1000}")).andExpect(status().isBadRequest());
  }

  @Test
  void getAccount() throws Exception {
    String uniqueAccountId = "Id-" + System.currentTimeMillis();
    Account account = new Account(uniqueAccountId, new BigDecimal("123.45"));
    this.accountsService.createAccount(account);
    this.mockMvc.perform(get("/v1/accounts/" + uniqueAccountId))
      .andExpect(status().isOk())
      .andExpect(
        content().string("{\"accountId\":\"" + uniqueAccountId + "\",\"balance\":123.45}"));
  }

  @Test
  void transfer_succeeds() throws Exception {
    transfer("{\"accountFrom\":\"Id-1\",\"accountTo\":\"Id-2\",\"transferAmount\":30}")
            .andExpect(status().isOk());

    assertThat(accountsService.getAccount("Id-1").getBalance()).isEqualByComparingTo("70");
    assertThat(accountsService.getAccount("Id-2").getBalance()).isEqualByComparingTo("80");
    verify(notificationService, times(2)).notifyAboutTransfer(any(Account.class), anyString());
  }

  @Test
  void transfer_insufficientFunds_isBadRequest() throws Exception {
    transfer("{\"accountFrom\":\"Id-1\",\"accountTo\":\"Id-2\",\"transferAmount\":100.01}")
            .andExpect(status().isBadRequest());

    assertThat(accountsService.getAccount("Id-1").getBalance()).isEqualByComparingTo("100");
    verifyNoInteractions(notificationService);
  }

  @Test
  void transfer_unknownAccount_isBadRequest() throws Exception {
    transfer("{\"accountFrom\":\"Id-1\",\"accountTo\":\"Nope\",\"transferAmount\":10}")
            .andExpect(status().isBadRequest());
    transfer("{\"accountFrom\":\"Nope\",\"accountTo\":\"Id-2\",\"transferAmount\":10}")
            .andExpect(status().isBadRequest());

    verifyNoInteractions(notificationService);
  }

  @Test
  void transfer_zeroOrNegativeAmount_isBadRequest() throws Exception {
    transfer("{\"accountFrom\":\"Id-1\",\"accountTo\":\"Id-2\",\"transferAmount\":0}")
            .andExpect(status().isBadRequest());
    transfer("{\"accountFrom\":\"Id-1\",\"accountTo\":\"Id-2\",\"transferAmount\":-5}")
            .andExpect(status().isBadRequest());

    assertThat(accountsService.getAccount("Id-1").getBalance()).isEqualByComparingTo("100");
    verifyNoInteractions(notificationService);
  }

  @Test
  void transfer_sameAccount_isBadRequest() throws Exception {
    transfer("{\"accountFrom\":\"Id-1\",\"accountTo\":\"Id-1\",\"transferAmount\":10}")
            .andExpect(status().isBadRequest());
  }

  @Test
  void transfer_missingOrBlankFields_isBadRequest() throws Exception {
    transfer("{\"accountTo\":\"Id-2\",\"transferAmount\":10}").andExpect(status().isBadRequest());
    transfer("{\"accountFrom\":\"Id-1\",\"transferAmount\":10}").andExpect(status().isBadRequest());
    transfer("{\"accountFrom\":\"Id-1\",\"accountTo\":\"Id-2\"}").andExpect(status().isBadRequest());
    transfer("{\"accountFrom\":\" \",\"accountTo\":\"Id-2\",\"transferAmount\":10}")
            .andExpect(status().isBadRequest());
  }

  @Test
  void transfer_noBody_isBadRequest() throws Exception {
    this.mockMvc.perform(post("/v1/accounts/transfer").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
  }

  private ResultActions transfer(String json) throws Exception {
    return this.mockMvc.perform(
            post("/v1/accounts/transfer").contentType(MediaType.APPLICATION_JSON).content(json));
  }
}
