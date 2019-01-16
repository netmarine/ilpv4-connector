package com.sappenin.interledger.ilpv4.connector.settings;

import org.immutables.value.Value;
import org.interledger.core.InterledgerAddress;
import org.interledger.core.InterledgerAddressPrefix;

import java.util.List;

/**
 * A view of the settings currently configured for this Connector.
 */
@Value.Immutable(intern = true)
@Value.Modifiable
public interface ConnectorSettings {

  String BEAN_NAME = "connectorSettings";

  /**
   * @return The ILP address of this connector.
   */
  InterledgerAddress getOperatorAddress();

  /**
   * The global address prefix for this operating environment.
   */
  @Value.Default
  default InterledgerAddressPrefix getGlobalPrefix() {
    return InterledgerAddressPrefix.of("g");
  }

  @Value.Default
  default boolean websocketServerEnabled() {
    return false;
  }

  /**
   * Which account should be used as the default route for all un-routed traffic. If empty, the default route is
   * disabled.
   */
  @Value.Default
  default GlobalRoutingSettings getGlobalRoutingSettings() {
    return ImmutableGlobalRoutingSettings.builder().build();
  }

  /**
   * Contains settings for all single-accounts connections configured for this Connector.
   *
   * @return An Collection of type {@link AccountSettings}.
   */
  List<AccountSettings> getAccountSettings();

}