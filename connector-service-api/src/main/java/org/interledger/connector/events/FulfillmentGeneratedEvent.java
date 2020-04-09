package org.interledger.connector.events;

import org.interledger.connector.accounts.AccountId;
import org.interledger.core.InterledgerFulfillment;
import org.interledger.core.InterledgerPreparePacket;
import org.interledger.stream.Denomination;
import org.interledger.stream.StreamPacket;

import org.immutables.value.Value;

/**
 * Event that is emitted when a connector in wallet-mode generates a fulfillment.
 */
@Value.Immutable
public interface FulfillmentGeneratedEvent {

  static ImmutableFulfillmentGeneratedEvent.Builder builder() {
    return ImmutableFulfillmentGeneratedEvent.builder();
  }

  /**
   * Incoming prepare packet from the previous hop
   * @return
   */
  InterledgerPreparePacket incomingPreparePacket();

  /**
   * Fulfillment condition from the receiver
   * @return
   */
  InterledgerFulfillment fulfillment();

  /**
   * Connector account for the next hop
   * @return
   */
  AccountId accountId();

  Denomination denomination();

  StreamPacket streamPacket();

}
