package com.sappenin.ilpv4.server.btp;

import com.sappenin.ilpv4.model.AccountId;
import org.springframework.web.socket.WebSocketSession;

import java.util.Objects;
import java.util.Optional;

/**
 * A session for BTP.
 */
public class BtpSession {

  static final String ACCOUNT_KEY = "ILP-Account";

  private final WebSocketSession webSocketSession;
  private final AccountId accountId;

  /**
   * Private constructor.
   *
   * @param webSocketSession
   */
  public BtpSession(final WebSocketSession webSocketSession) {
    this.webSocketSession = webSocketSession;

    this.accountId = Optional.ofNullable(
      Objects.requireNonNull(webSocketSession).getAttributes().get(ACCOUNT_KEY)
    )
      .map(Object::toString)
      .map(AccountId::of)
      .orElseThrow(() -> new RuntimeException("No Account found in WebSocket Session!"));
  }

  public AccountId getAccountId() {
    return this.accountId;
  }
}