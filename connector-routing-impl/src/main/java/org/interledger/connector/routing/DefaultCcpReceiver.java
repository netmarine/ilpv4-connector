package org.interledger.connector.routing;

import org.interledger.connector.accounts.AccountId;
import org.interledger.connector.ccp.CcpConstants;
import org.interledger.connector.ccp.CcpRouteControlRequest;
import org.interledger.connector.ccp.CcpRoutePathPart;
import org.interledger.connector.ccp.CcpRouteUpdateRequest;
import org.interledger.connector.ccp.CcpSyncMode;
import org.interledger.connector.ccp.CcpWithdrawnRoute;
import org.interledger.connector.ccp.ImmutableCcpRouteControlRequest;
import org.interledger.connector.link.CircuitBreakingLink;
import org.interledger.connector.settings.ConnectorSettings;
import org.interledger.core.InterledgerAddressPrefix;
import org.interledger.core.InterledgerErrorCode;
import org.interledger.core.InterledgerPreparePacket;
import org.interledger.core.InterledgerProtocolException;
import org.interledger.core.InterledgerRejectPacket;
import org.interledger.core.InterledgerResponsePacket;
import org.interledger.encoding.asn.framework.CodecContext;
import org.interledger.link.Link;
import org.interledger.link.exceptions.LinkException;
import org.interledger.link.http.IlpOverHttpLink;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link CcpReceiver}.
 */
public class DefaultCcpReceiver implements CcpReceiver {

  private final Logger logger = LoggerFactory.getLogger(this.getClass());

  private final RoutingTable<IncomingRoute> incomingRoutes;
  private final Supplier<ConnectorSettings> connectorSettingsSupplier;
  private final CodecContext ccpCodecContext;

  // Info for communicating with the remote peer.
  private final AccountId peerAccountId;
  private final Link link;

  // Current routing table id used by our peer. We'll reset our epoch if this changes.
  private Optional<RoutingTableId> routingTableId = Optional.empty();

  //Epoch index up to which our peer has sent updates
  private int epoch = 0;

  // Contains the identifier used used by our peer. We'll reset the epoch to 0 if the identifier changes.
  private Instant routingTableExpiry = Instant.EPOCH;

  /**
   * Required-args Constructor. Note that each instance of a CCP Receiver should have its own incoming routing table.
   */
  public DefaultCcpReceiver(
    final Supplier<ConnectorSettings> connectorSettingsSupplier,
    final AccountId peerAccountId,
    final Link link,
    final CodecContext ccpCodecContext
  ) {
    this(
      connectorSettingsSupplier,
      peerAccountId,
      link,
      ccpCodecContext,
      new InMemoryRoutingTable<>()
    );
  }

  @VisibleForTesting
  DefaultCcpReceiver(
    final Supplier<ConnectorSettings> connectorSettingsSupplier,
    final AccountId peerAccountId,
    final Link link,
    final CodecContext ccpCodecContext,
    final RoutingTable<IncomingRoute> incomingRoutes
  ) {
    this.connectorSettingsSupplier = Objects.requireNonNull(connectorSettingsSupplier);
    this.ccpCodecContext = Objects.requireNonNull(ccpCodecContext);
    this.peerAccountId = peerAccountId;
    this.link = Objects.requireNonNull(link);
    this.incomingRoutes = Objects.requireNonNull(incomingRoutes);
  }

  @Override
  public List<InterledgerAddressPrefix> handleRouteUpdateRequest(
    final CcpRouteUpdateRequest routeUpdateRequest
  ) {
    Objects.requireNonNull(routeUpdateRequest);

    // Extend the routingTableExpiry of the routing-table held by this receiver...
    this.bump(routeUpdateRequest.holdDownTime());

    // If the remote peer/account has a new routing table Id, then we reset the epoch so that we can take all
    // routes from the remote.
    if (!this.routingTableId.isPresent() || !this.routingTableId.get().equals(routeUpdateRequest.routingTableId())) {
      logger.debug("Saw new routing table. oldId={} newId={}",
        this.routingTableId.map(RoutingTableId::value).map(UUID::toString).orElse("n/a"),
        routeUpdateRequest.routingTableId()
      );
      this.epoch = 0;
    }

    if (routeUpdateRequest.fromEpochIndex() > this.epoch) {
      // There is a gap, we need to go back to the last epoch we have
      logger.debug("Gap in routing updates. expectedEpoch={} actualFromEpoch={}",
        this.epoch, routeUpdateRequest.fromEpochIndex()
      );
      return Collections.emptyList();
    }

    if (this.epoch > routeUpdateRequest.toEpochIndex()) {
      // This routing update is older than what we already have
      logger.debug(
        "Old routing update, ignoring. expectedEpoch={} actualToEpoch={}",
        this.epoch, routeUpdateRequest.toEpochIndex()
      );
      return Collections.emptyList();
    }

    // just a heartbeat
    if (routeUpdateRequest.newRoutes().size() == 0 && routeUpdateRequest.withdrawnRoutePrefixes().size() == 0) {
      logger.debug("Pure heartbeat. fromEpoch={} toEpoch={}",
        routeUpdateRequest.fromEpochIndex(), routeUpdateRequest.toEpochIndex()
      );
      this.epoch = routeUpdateRequest.toEpochIndex();
      return Collections.emptyList();
    }

    final ImmutableList.Builder<InterledgerAddressPrefix> changedPrefixesBuilder = ImmutableList.builder();

    // Withdrawn Routes...
    if (routeUpdateRequest.withdrawnRoutePrefixes().size() > 0) {
      logger.debug("Informed of no-longer-reachable routes. count={} routes={}",
        routeUpdateRequest.withdrawnRoutePrefixes().size(), routeUpdateRequest.withdrawnRoutePrefixes()
      );

      routeUpdateRequest.withdrawnRoutePrefixes().stream()
        .map(CcpWithdrawnRoute::prefix)
        .forEach(withdrawnRoutePrefix -> {
          this.incomingRoutes.removeRoute(withdrawnRoutePrefix);
          changedPrefixesBuilder.add(withdrawnRoutePrefix);
        });
    }

    // New Routes
    routeUpdateRequest.newRoutes().stream()
      .map(ccpNewRoute -> ImmutableIncomingRoute.builder()
        .peerAccountId(peerAccountId)
        .routePrefix(ccpNewRoute.prefix())
        .path(
          ccpNewRoute.path().stream()
            .map(CcpRoutePathPart::routePathPart)
            .collect(Collectors.toList())
        )
        .auth(ccpNewRoute.auth())
        .build()
      )
      .forEach(newIncomingRoute -> {
        if (this.incomingRoutes.addRoute(newIncomingRoute) != null) {
          changedPrefixesBuilder.add(newIncomingRoute.routePrefix());
        }
      });

    this.epoch = routeUpdateRequest.toEpochIndex();

    final List<InterledgerAddressPrefix> changedPrefixes = changedPrefixesBuilder.build();
    logger.debug("Applied route update. changedPrefixesCount={} fromEpoch={} toEpoch={}",
      changedPrefixes.size(), routeUpdateRequest.fromEpochIndex(), routeUpdateRequest.toEpochIndex()
    );
    return changedPrefixes;
  }

  public InterledgerResponsePacket sendRouteControl() {
    Preconditions.checkNotNull(link, "Link must be assigned before using a CcpReceiver!");

    final CcpRouteControlRequest request = ImmutableCcpRouteControlRequest.builder()
      .mode(CcpSyncMode.MODE_SYNC)
      .lastKnownRoutingTableId(this.routingTableId)
      .lastKnownEpoch(this.epoch)
      // No features....
      .build();

    final InterledgerPreparePacket preparePacket = InterledgerPreparePacket.builder()
      .amount(UnsignedLong.ZERO)
      .destination(CcpConstants.CCP_CONTROL_DESTINATION_ADDRESS)
      .executionCondition(CcpConstants.PEER_PROTOCOL_EXECUTION_CONDITION)
      .expiresAt(Instant.now().plus(connectorSettingsSupplier.get().globalRoutingSettings().routeExpiry()))
      .data(serializeCcpPacket(request))
      .build();

    // Link handles retry, if any...
    try {
      logger.info(
        "Sending Ccp RouteControl Request to peer={} url={} CcpRouteControlRequest={} InterledgerPreparePacket={}",
        this.peerAccountId,
        this.getOutgoingUrl(link),
        request,
        preparePacket
      );

      return this.link.sendPacket(preparePacket)
        .handleAndReturn(fulfillPacket -> {
          logger.debug("Successfully sent route control message to peer={}", peerAccountId);
        }, rejectPacket -> {
          logger.debug("Route control message was rejected by peer={}. rejectPacket={}",
            peerAccountId,
            rejectPacket.getMessage()
          );
        });

    } catch (InterledgerProtocolException e) {
      final InterledgerRejectPacket rejectPacket = e.getInterledgerRejectPacket();
      logger.warn("Route control message was rejected by peer={}. rejectPacket={}", peerAccountId, rejectPacket);
      return rejectPacket;
    } catch (LinkException e) {
      final InterledgerRejectPacket rejectPacket = InterledgerRejectPacket.builder()
        .code(InterledgerErrorCode.T01_PEER_UNREACHABLE)
        .message(String.format("Route control message could not be sent to peer=%s.", peerAccountId))
        .triggeredBy(connectorSettingsSupplier.get().operatorAddress())
        .build();
      logger.error(String.format(
        "Route control message could not be sent to peer=%s. Rejecting with rejectPacket=%s",
        peerAccountId, rejectPacket
        ),
        e // Exception is here so it gets logged properly.
      );
      return rejectPacket;
    } catch (Exception e) {
      final InterledgerRejectPacket rejectPacket = InterledgerRejectPacket.builder()
        .code(InterledgerErrorCode.F99_APPLICATION_ERROR)
        .message(
          String.format("Route control message could not be sent to peer=%s due to unknown error. error=%s",
            this.peerAccountId,
            e.getMessage()
          )
        )
        .triggeredBy(connectorSettingsSupplier.get().operatorAddress())
        .build();
      logger.error(String.format(
        "Route control message could not be sent to peer=%s due to unknown error. Rejecting with rejectPacket=%s",
        peerAccountId, rejectPacket
        ),
        e // Exception is here so it gets logged properly.
      );
      return rejectPacket;
    }
  }

  @Override
  public void forEachIncomingRoute(final BiConsumer<InterledgerAddressPrefix, IncomingRoute> action) {
    this.incomingRoutes.forEach(action);
  }

  @Override
  public Optional<IncomingRoute> getIncomingRouteForPrefix(final InterledgerAddressPrefix addressPrefix) {
    Objects.requireNonNull(addressPrefix);
    return incomingRoutes.getRouteByPrefix(addressPrefix);
  }

  /**
   * Bump up the routingTableExpiry of this routing table by the number of milliseconds indicated in {@code
   * holdDownTimeMillis}.
   *
   * @param holdDownTimeMillis the number of millis to extend the expiration of the current routing table.
   */
  protected void bump(final long holdDownTimeMillis) {

    final Instant requestedExpiry = Instant.now().plusMillis(holdDownTimeMillis);

    if (this.routingTableExpiry.isBefore(requestedExpiry)) {
      this.routingTableExpiry = requestedExpiry;
    }
  }

  @VisibleForTesting
  protected byte[] serializeCcpPacket(final CcpRouteControlRequest ccpRouteControlRequest) {
    Objects.requireNonNull(ccpRouteControlRequest);

    try {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      ccpCodecContext.write(ccpRouteControlRequest, outputStream);
      return outputStream.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @VisibleForTesting
  protected Instant getRoutingTableExpiry() {
    return this.routingTableExpiry;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", DefaultCcpReceiver.class.getSimpleName() + "[", "]")
      .add("peerAccountId=" + peerAccountId)
      .add("link=" + link)
      .add("routingTableId=" + routingTableId)
      .add("epoch=" + epoch)
      .add("routingTableExpiry=" + routingTableExpiry)
      .toString();
  }

  /**
   * Determines if a particular link has a URL inside of it, for logging purposes.
   *
   * @param link A {@link Link} to determine the class-type of.
   *
   * @return {@code true} if the link has a URL; {@code false} otherwise.
   */
  private String getOutgoingUrl(final Link link) {
    Objects.requireNonNull(link);

    if (IlpOverHttpLink.class.isAssignableFrom(link.getClass())) {
      return ((IlpOverHttpLink) link).getOutgoingUrl().toString();
    } else if (CircuitBreakingLink.class.isAssignableFrom(link.getClass())) {
      final CircuitBreakingLink circuitBreakingLink = (CircuitBreakingLink) link;
      if (IlpOverHttpLink.class.isAssignableFrom(circuitBreakingLink.getLinkDelegateTyped().getClass())) {
        return circuitBreakingLink.<IlpOverHttpLink>getLinkDelegateTyped().getOutgoingUrl().toString();
      }
    }
    return "n/a";
  }
}
