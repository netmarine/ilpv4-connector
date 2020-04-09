package org.interledger.connector.opa.config;

import org.interledger.connector.opa.model.OpenPaymentsMetadata;
import org.interledger.core.InterledgerAddress;
import org.interledger.link.Link;

import org.immutables.value.Value;

/**
 * A view of the settings currently configured for this Open Payments Server.
 */
@Value.Immutable(intern = true)
public interface OpenPaymentsSettings {

  static ImmutableOpenPaymentsSettings.Builder builder() {
    return ImmutableOpenPaymentsSettings.builder();
  }

  /**
   * The ILP address of the connector that this Open Payments server sits next to.
   *
   * Note that this is a different configuration property than the node address in a connector config,
   * but they should hold the same value.
   *
   * @return The {@link InterledgerAddress} of the Connector that Open Payments server sits next to.
   */
  @Value.Default
  default InterledgerAddress ilpOperatorAddress() {
    return Link.SELF;
  }


  /**
   * Discoverable Open Payments Metadata that can be used to set up Open Payments flows.
   */
  @Value.Default
  default OpenPaymentsMetadata metadata() {
    return OpenPaymentsMetadata.builder().build();
  }
}