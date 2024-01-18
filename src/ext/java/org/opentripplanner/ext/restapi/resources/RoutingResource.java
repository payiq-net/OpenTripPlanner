package org.opentripplanner.ext.restapi.resources;

import static org.opentripplanner.api.common.LocationStringParser.fromOldStyleString;
import static org.opentripplanner.ext.restapi.resources.RequestToPreferencesMapper.setIfNotNull;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import org.opentripplanner.api.parameter.QualifiedModeSet;
import org.opentripplanner.ext.dataoverlay.api.DataOverlayParameters;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.framework.lang.StringUtils;
import org.opentripplanner.framework.time.DurationUtils;
import org.opentripplanner.framework.time.ZoneIdFallback;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.preference.ItineraryFilterDebugProfile;
import org.opentripplanner.routing.api.request.request.filter.SelectRequest;
import org.opentripplanner.routing.api.request.request.filter.TransitFilterRequest;
import org.opentripplanner.routing.core.BicycleOptimizeType;
import org.opentripplanner.standalone.api.OtpServerRequestContext;
import org.opentripplanner.standalone.config.framework.file.ConfigFileLoader;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;
import org.opentripplanner.standalone.config.routerequest.RouteRequestConfig;
import org.opentripplanner.transit.model.basic.MainAndSubMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class defines all the JAX-RS query parameters for a path search as fields, allowing them to
 * be inherited by other REST resource classes (the trip planner and the Analyst WMS or tile
 * resource). They will be properly included in API docs generated by Enunciate. This implies that
 * the concrete REST resource subclasses will be request-scoped rather than singleton-scoped.
 * <p>
 * All defaults should be specified in the RouteRequest, NOT as annotations on the query
 * parameters. JSON router configuration can then overwrite those built-in defaults, and only the
 * fields of the resulting prototype routing request for which query parameters are found are
 * overwritten here. This establishes a priority chain: RouteRequest field initializers, then JSON
 * router config, then query parameters.
 *<p>
 * See the configuration for documentation of each field.
 */
@SuppressWarnings({ "FieldMayBeFinal", "unused" })
public abstract class RoutingResource {

  private static final Logger LOG = LoggerFactory.getLogger(RoutingResource.class);

  /**
   * The start location -- either latitude, longitude pair in degrees or a Vertex label. For
   * example, <code>40.714476,-74.005966</code> or
   * <code>mtanyctsubway_A27_S</code>.
   */
  @QueryParam("fromPlace")
  protected String fromPlace;

  /** The end location (see fromPlace for format). */
  @QueryParam("toPlace")
  protected String toPlace;

  /**
   * An ordered list of intermediate locations to be visited (see the fromPlace for format).
   * Parameter can be specified multiple times.
   *
   * @deprecated TODO OTP2 - Regression. Not currently working in OTP2. Must be re-implemented
   * - using raptor.
   */
  @Deprecated
  @QueryParam("intermediatePlaces")
  protected List<String> intermediatePlaces;

  /** The date that the trip should depart (or arrive, for requests where arriveBy is true). */
  @QueryParam("date")
  protected String date;

  /** The time that the trip should depart (or arrive, for requests where arriveBy is true). */
  @QueryParam("time")
  protected String time;

  /**
   * The length of the search-window in seconds. This parameter is optional.
   * <p>
   * The search-window is defined as the duration between the earliest-departure-time(EDT) and the
   * latest-departure-time(LDT). OTP will search for all itineraries in this departure window. If
   * {@code arriveBy=true} the {@code dateTime} parameter is the latest-arrival-time, so OTP will
   * dynamically calculate the EDT. Using a short search-window is faster than using a longer one,
   * but the search duration is not linear. Using a \"too\" short search-window will waste resources
   * server side, while using a search-window that is too long will be slow.
   * <p>
   * OTP will dynamically calculate a reasonable value for the search-window, if not provided. The
   * calculation comes with a significant overhead (10-20% extra). Whether you should use the
   * dynamic calculated value or pass in a value depends on your use-case. For a travel planner in a
   * small geographical area, with a dense network of public transportation, a fixed value between
   * 40 minutes and 2 hours makes sense. To find the appropriate search-window, adjust it so that
   * the number of itineraries on average is around the wanted {@code numItineraries}. Make sure you
   * set the {@code numItineraries} to a high number while testing. For a country wide area like
   * Norway, using the dynamic search-window is the best.
   * <p>
   * When paginating, the search-window is calculated using the {@code numItineraries} in the
   * original search together with statistics from the search for the last page. This behaviour is
   * configured server side, and can not be overridden from the client.
   * <p>
   * The search-window used is returned to the response metadata as {@code searchWindowUsed} for
   * debugging purposes.
   */
  @QueryParam("searchWindow")
  protected String searchWindow;

  /**
   * Use the cursor to go to the next "page" of itineraries. Copy the cursor from the last response
   * and keep the original request as is. This will enable you to search for itineraries in the next
   * or previous time-window.
   * <p>
   * This is an optional parameter.
   */
  @QueryParam("pageCursor")
  public String pageCursor;

  /**
   * Search for the best trip options within a time window. If {@code true} two itineraries are
   * considered optimal if one is better on arrival time(earliest wins) and the other is better on
   * departure time(latest wins).
   * <p>
   * In combination with {@code arriveBy} this parameter cover the following 3 use cases:
   * <ul>
   *   <li>
   *     Traveler want to find thee best alternative within a time window. Set
   *     {@code timetableView=true} and {@code arriveBy=false}.  This is the default, and if
   *     the intention of the traveler is unknown it gives the best result, because it includes
   *     the two next use-cases. Setting the {@code arriveBy=false}, covers the same use-case,
   *     but the input time is interpreted as latest-arrival-time, and not
   *     earliest-departure-time. This works great with paging, request next/previous time-window.
   *   </li>
   *   <li>
   *     Traveler want to find the best alternative with departure after a specific time.
   *     For example: I am at the station now and want to get home as quickly as possible.
   *     Set {@code timetableView=false} and {@code arriveBy=false}. Do not support paging.
   *   </li>
   *   <li>
   *     Traveler want to find the best alternative with arrival before specific time. For
   *     example going to a meeting. Do not support paging.
   *     Set {@code timetableView=false} and {@code arriveBy=true}.
   *   </li>
   * </ul>
   * Default: true
   */
  @QueryParam("timetableView")
  public Boolean timetableView;

  @Deprecated
  @QueryParam("arriveBy")
  protected Boolean arriveBy;

  /**
   * Whether the trip must be wheelchair accessible.
   *
   * @deprecated TODO OTP2 Regression. Not currently working in OTP2. This is not implemented
   * in Raptor yet.
   */
  @Deprecated
  @QueryParam("wheelchair")
  protected Boolean wheelchair;

  /**
   * The maximum time (in seconds) of pre-transit travel when using drive-to-transit (park and ride
   * or kiss and ride). Defaults to unlimited.
   * <p>
   * See https://github.com/opentripplanner/OpenTripPlanner/issues/2886
   *
   * @deprecated TODO OTP2 - Regression. Not currently working in OTP2.
   */
  @Deprecated
  @QueryParam("maxPreTransitTime")
  protected Integer maxPreTransitTime;

  /**
   * A multiplier for how bad walking with a bike is, compared to being in transit for equal lengths
   * of time. Defaults to 3.
   */
  @QueryParam("bikeWalkingReluctance")
  protected Double bikeWalkingReluctance;

  @QueryParam("walkReluctance")
  protected Double walkReluctance;

  @QueryParam("bikeReluctance")
  protected Double bikeReluctance;

  @QueryParam("carReluctance")
  protected Double carReluctance;

  /**
   * How much worse is waiting for a transit vehicle than being on a transit vehicle, as a
   * multiplier. The default value treats wait and on-vehicle time as the same.
   * <p>
   * It may be tempting to set this higher than walkReluctance (as studies often find this kind of
   * preferences among riders) but the planner will take this literally and walk down a transit line
   * to avoid waiting at a stop. This used to be set less than 1 (0.95) which would make waiting
   * offboard preferable to waiting onboard in an interlined trip. That is also undesirable.
   * <p>
   * If we only tried the shortest possible transfer at each stop to neighboring stop patterns, this
   * problem could disappear.
   */
  @QueryParam("waitReluctance")
  protected Double waitReluctance;

  @QueryParam("walkSpeed")
  protected Double walkSpeed;

  @QueryParam("bikeSpeed")
  protected Double bikeSpeed;

  @QueryParam("bikeWalkingSpeed")
  protected Double bikeWalkingSpeed;

  @QueryParam("bikeSwitchTime")
  protected Integer bikeSwitchTime;

  @QueryParam("bikeSwitchCost")
  protected Integer bikeSwitchCost;

  @QueryParam("triangleSafetyFactor")
  protected Double triangleSafetyFactor;

  @QueryParam("triangleSlopeFactor")
  protected Double triangleSlopeFactor;

  @QueryParam("triangleTimeFactor")
  protected Double triangleTimeFactor;

  /**
   * The set of characteristics that the user wants to optimize for. @See OptimizeType.
   *
   * @deprecated TODO OTP2 this should be completely removed and done only with individual cost
   * parameters
   * Also: apparently OptimizeType only affects BICYCLE mode traversal of
   * street segments. If this is the case it should be very well
   * documented and carried over into the Enum name.
   */
  @Deprecated
  @QueryParam("optimize")
  protected BicycleOptimizeType bikeOptimizeType;

  /**
   * The set of modes that a user is willing to use, with qualifiers stating whether vehicles should
   * be parked, rented, etc.
   * <p>
   * The possible values of the comma-separated list are:
   *
   * <ul>
   *  <li>WALK</li>
   *  <li>TRANSIT</li>
   *  <li>BICYCLE</li>
   *  <li>BICYCLE_RENT</li>
   *  <li>BICYCLE_PARK</li>
   *  <li>CAR</li>
   *  <li>CAR_PARK</li>
   *  <li>TRAM</li>
   *  <li>SUBWAY</li>
   *  <li>RAIL</li>
   *  <li>BUS</li>
   *  <li>CABLE_CAR</li>
   *  <li>FERRY</li>
   *  <li>GONDOLA</li>
   *  <li>FUNICULAR</li>
   *  <li>AIRPLANE</li>
   * </ul>
   * <p>
   *   For a more complete discussion of this parameter see
   *   <a href="http://docs.opentripplanner.org/en/latest/Configuration/#routing-modes">Routing modes</a>.
   */
  @QueryParam("mode")
  protected QualifiedModeSet modes;

  /**
   * The minimum time, in seconds, between successive trips on different vehicles. This is designed
   * to allow for imperfect schedule adherence. This is a minimum; transfers over longer distances
   * might use a longer time.
   *
   * @deprecated TODO OTP2: Needs to be implemented
   */
  @Deprecated
  @QueryParam("minTransferTime")
  protected Integer minTransferTime;

  /** The maximum number of possible itineraries to return. */
  @QueryParam("numItineraries")
  protected Integer numItineraries;

  /**
   * The comma-separated list of preferred agencies.
   *
   * @deprecated TODO OTP2: Needs to be implemented
   */
  @Deprecated
  @QueryParam("preferredAgencies")
  protected String preferredAgencies;

  /**
   * The comma-separated list of unpreferred agencies.
   *
   * @deprecated TODO OTP2: Needs to be implemented
   */
  @Deprecated
  @QueryParam("unpreferredAgencies")
  protected String unpreferredAgencies;

  /**
   * The comma-separated list of banned agencies.
   */
  @QueryParam("bannedAgencies")
  protected String bannedAgencies;

  /**
   * Functions the same as banned agencies, except only the listed agencies are allowed.
   */
  @QueryParam("whiteListedAgencies")
  protected String whiteListedAgencies;

  /**
   * Whether intermediate stops -- those that the itinerary passes in a vehicle, but does not board
   * or alight at -- should be returned in the response.  For example, on a Q train trip from
   * Prospect Park to DeKalb Avenue, whether 7th Avenue and Atlantic Avenue should be included.
   */
  @QueryParam("showIntermediateStops")
  @DefaultValue("false")
  protected Boolean showIntermediateStops;

  @QueryParam("walkBoardCost")
  protected Integer walkBoardCost;

  @QueryParam("bikeBoardCost")
  protected Integer bikeBoardCost;

  @QueryParam("walkSafetyFactor")
  protected Double walkSafetyFactor;

  @QueryParam("allowKeepingRentedBicycleAtDestination")
  protected Boolean allowKeepingRentedBicycleAtDestination;

  @QueryParam("keepingRentedBicycleAtDestinationCost")
  protected Double keepingRentedBicycleAtDestinationCost;

  @QueryParam("allowedVehicleRentalNetworks")
  protected Set<String> allowedVehicleRentalNetworks;

  @QueryParam("bannedVehicleRentalNetworks")
  protected Set<String> bannedVehicleRentalNetworks;

  @QueryParam("bikeParkTime")
  protected Integer bikeParkTime;

  @QueryParam("bikeParkCost")
  protected Integer bikeParkCost;

  @QueryParam("carParkTime")
  protected Integer carParkTime = 60;

  @QueryParam("carParkCost")
  protected Integer carParkCost = 120;

  @QueryParam("requiredVehicleParkingTags")
  protected Set<String> requiredVehicleParkingTags = Set.of();

  @QueryParam("bannedVehicleParkingTags")
  protected Set<String> bannedVehicleParkingTags = Set.of();

  /**
   * The comma-separated list of banned routes. The format is agency_[routename][_routeid], so
   * TriMet_100 (100 is route short name) or Trimet__42 (two underscores, 42 is the route internal
   * ID).
   */
  @Deprecated
  @QueryParam("bannedRoutes")
  protected String bannedRoutes;

  /**
   * Functions the same as bannnedRoutes, except only the listed routes are allowed.
   */
  @QueryParam("whiteListedRoutes")
  @Deprecated
  protected String whiteListedRoutes;

  /**
   * The list of preferred routes. The format is agency_[routename][_routeid], so TriMet_100 (100 is
   * route short name) or Trimet__42 (two underscores, 42 is the route internal ID).
   *
   * @deprecated TODO OTP2 Needs to be implemented
   */
  @Deprecated
  @QueryParam("preferredRoutes")
  protected String preferredRoutes;

  /**
   * The list of unpreferred routes. The format is agency_[routename][_routeid], so TriMet_100 (100
   * is route short name) or Trimet__42 (two underscores, 42 is the route internal ID).
   *
   * @deprecated TODO OTP2 Needs to be implemented
   */
  @Deprecated
  @QueryParam("unpreferredRoutes")
  protected String unpreferredRoutes;

  /**
   * Penalty added for using every route that is not preferred if user set any route as preferred,
   * i.e. number of seconds that we are willing to wait for preferred route.
   *
   * @deprecated TODO OTP2 Needs to be implemented
   */
  @Deprecated
  @QueryParam("otherThanPreferredRoutesPenalty")
  protected Integer otherThanPreferredRoutesPenalty;

  /**
   * The comma-separated list of banned trips.  The format is feedId:tripId
   */
  @QueryParam("bannedTrips")
  protected String bannedTrips;

  /**
   * A comma-separated list of banned stops. A stop is banned by ignoring its pre-board and
   * pre-alight edges. This means the stop will be reachable via the street network. Also, it is
   * still possible to travel through the stop. Just boarding and alighting is prohibited. The
   * format is agencyId_stopId, so: TriMet_2107
   *
   * @deprecated TODO OTP2 This no longer works in OTP2, see issue #2843.
   */
  @Deprecated
  @QueryParam("bannedStops")
  protected String bannedStops;

  /**
   * A comma-separated list of banned stops. A stop is banned by ignoring its pre-board and
   * pre-alight edges. This means the stop will be reachable via the street network. It is not
   * possible to travel through the stop. For example, this parameter can be used when a train
   * station is destroyed, such that no trains can drive through the station anymore. The format is
   * agencyId_stopId, so: TriMet_2107
   *
   * @deprecated TODO OTP2 This no longer works in OTP2, see issue #2843.
   */
  @Deprecated
  @QueryParam("bannedStopsHard")
  protected String bannedStopsHard;

  @QueryParam("transferPenalty")
  protected Integer transferPenalty;

  /**
   * An additional penalty added to boardings after the first when the transfer is not preferred.
   * Preferred transfers also include timed transfers. The value is in OTP's internal weight units,
   * which are roughly equivalent to seconds. Set this to a high value to discourage transfers that
   * are not preferred. Of course, transfers that save significant time or walking will still be
   * taken. When no preferred or timed transfer is defined, this value is ignored.
   * <p>
   * TODO OTP2 This JavaDoc needs clarification. What is a "preferred" Transfer, the GTFS
   *           specification do not have "preferred Transfers". The GTFS spec transfer
   *           type 0 is _Recommended transfer point_ - is this what is meant?
   *
   * @deprecated TODO OTP2 Regression. Not currently working in OTP2. We might not implement the
   * old functionality the same way, but we will try to map this parameter
   * so it does work similar as before.
   */
  @Deprecated
  @QueryParam("nonpreferredTransferPenalty")
  protected Integer nonpreferredTransferPenalty;

  /**
   * The maximum number of transfers (that is, one plus the maximum number of boardings) that a trip
   * will be allowed.
   * <p>
   * Consider using the {@link #transferPenalty} instead of this parameter.
   * <p>
   * See https://github.com/opentripplanner/OpenTripPlanner/issues/2886
   *
   * @deprecated Use {@link #maxAdditionalTransfers} instead to pass in the max number of
   * additional/extra transfers relative to the best trip (with the fewest possible transfers)
   * within constraint of the other search parameters. This might be too complicated to explain to
   * the customer, so you might stick to the old limit, but that has side-effects where you might
   * not find any trips on a day when a critical part of the trip is not available, because of some
   * real-time disruption.
   */
  @Deprecated
  @QueryParam("maxTransfers")
  protected Integer maxTransfers;

  /**
   * The maximum number of additional transfers in addition to the result with the least number of transfers.
   * <p>
   * Consider using the {@link #transferPenalty} instead of this parameter.
   */
  @QueryParam("maxAdditionalTransfers")
  protected Integer maxAdditionalTransfers;

  /**
   * If true, goal direction is turned off and a full path tree is built (specify only once)
   *
   * @Deprecated - This is not supported in OTP2 any more.
   */
  @Deprecated
  @QueryParam("batch")
  protected Boolean batch;

  /**
   * A transit stop required to be the first stop in the search (AgencyId_StopId)
   *
   * @deprecated TODO OTP2 Is this in use, what is is used for. It seems to overlap with
   * the fromPlace parameter. Is is used for onBoard routing only?
   */
  @Deprecated
  @QueryParam("startTransitStopId")
  protected String startTransitStopId;

  /**
   * A transit trip acting as a starting "state" for depart-onboard routing (AgencyId_TripId)
   *
   * @deprecated TODO OTP2 Regression. Not currently working in OTP2. We might not implement the
   * old functionality the same way, but we will try to map this parameter
   * so it does work similar as before.
   */
  @Deprecated
  @QueryParam("startTransitTripId")
  protected String startTransitTripId;

  /**
   * When subtracting initial wait time, do not subtract more than this value, to prevent overly
   * optimistic trips. Reasoning is that it is reasonable to delay a trip start 15 minutes to make a
   * better trip, but that it is not reasonable to delay a trip start 15 hours; if that is to be
   * done, the time needs to be included in the trip time. This number depends on the transit
   * system; for transit systems where trips are planned around the vehicles, this number can be
   * much higher. For instance, it's perfectly reasonable to delay one's trip 12 hours if one is
   * taking a cross-country Amtrak train from Emeryville to Chicago. Has no effect in stock OTP,
   * only in Analyst.
   * <p>
   * A value of 0 means that initial wait time will not be subtracted out (will be clamped to 0). A
   * value of -1 (the default) means that clamping is disabled, so any amount of initial wait time
   * will be subtracted out.
   *
   * @deprecated This parameter is not in use any more.
   */
  @Deprecated
  @QueryParam("clampInitialWait")
  protected Long clampInitialWait;

  /**
   * THIS PARAMETER IS NO LONGER IN USE.
   * <p>
   * If true, this trip will be reverse-optimized on the fly. Otherwise, reverse-optimization will
   * occur once a trip has been chosen (in Analyst, it will not be done at all).
   *
   * @deprecated This parameter is not in use any more after the transit search switched from AStar
   * to Raptor.
   */
  @Deprecated
  @QueryParam("reverseOptimizeOnTheFly")
  protected Boolean reverseOptimizeOnTheFly;

  /**
   * The number of seconds to add before boarding a transit leg. It is recommended to use the
   * {@code boardTimes} in the {@code router-config.json} to set this for each mode.
   * <p>
   * Unit is seconds. Default value is 0.
   */
  @QueryParam("boardSlack")
  protected Integer boardSlack;

  /**
   * The number of seconds to add after alighting a transit leg. It is recommended to use the
   * {@code alightTimes} in the {@code router-config.json} to set this for each mode.
   * <p>
   * Unit is seconds. Default value is 0.
   */
  @QueryParam("alightSlack")
  protected Integer alightSlack;

  @QueryParam("locale")
  private String locale;

  /**
   * If true, realtime updates are ignored during this search.
   *
   * @deprecated TODO OTP2 Regression. Not currently working in OTP2.
   */
  @Deprecated
  @QueryParam("ignoreRealtimeUpdates")
  protected Boolean ignoreRealtimeUpdates;

  /**
   * If true, the remaining weight heuristic is disabled. Currently only implemented for the long
   * distance path service.
   */
  @QueryParam("disableRemainingWeightHeuristic")
  protected Boolean disableRemainingWeightHeuristic;

  /**
   * See https://github.com/opentripplanner/OpenTripPlanner/issues/2886
   *
   * @deprecated TODO OTP2 This is not useful as a search parameter, but could be used as a
   * post search filter to reduce number of itineraries down to an
   * acceptable number, but there are probably better ways to do that.
   */
  @Deprecated
  @QueryParam("maxHours")
  private Double maxHours;

  /**
   * See https://github.com/opentripplanner/OpenTripPlanner/issues/2886
   *
   * @deprecated see {@link #maxHours}
   */
  @QueryParam("useRequestedDateTimeInMaxHours")
  @Deprecated
  private Boolean useRequestedDateTimeInMaxHours;

  /**
   * @deprecated This is not supported in OTP2.
   */
  @QueryParam("disableAlertFiltering")
  @Deprecated
  private Boolean disableAlertFiltering;

  @QueryParam("debugItineraryFilter")
  protected ItineraryFilterDebugProfile debugItineraryFilter;

  @QueryParam("groupSimilarityKeepOne")
  Double groupSimilarityKeepOne;

  @QueryParam("groupSimilarityKeepThree")
  Double groupSimilarityKeepThree;

  @QueryParam("groupedOtherThanSameLegsMaxCostMultiplier")
  Double groupedOtherThanSameLegsMaxCostMultiplier;

  @QueryParam("transitGeneralizedCostLimitFunction")
  String transitGeneralizedCostLimitFunction;

  @QueryParam("transitGeneralizedCostLimitIntervalRelaxFactor")
  Double transitGeneralizedCostLimitIntervalRelaxFactor;

  @QueryParam("nonTransitGeneralizedCostLimitFunction")
  String nonTransitGeneralizedCostLimitFunction;

  @QueryParam("geoidElevation")
  protected Boolean geoidElevation;

  /**
   * @deprecated Support has been removed.
   */
  @Deprecated
  @QueryParam("useVehicleParkingAvailabilityInformation")
  protected Boolean useVehicleParkingAvailabilityInformation;

  @QueryParam("relaxTransitGroupPriority")
  protected String relaxTransitGroupPriority;

  /**
   * Whether non-optimal transit paths at the destination should be returned.
   * This is optional. Use values between 1.0 and 2.0. For example to relax 10% use 1.1.
   * Values greater than 2.0 are not supported, due to performance reasons.
   */
  @QueryParam("relaxTransitSearchGeneralizedCostAtDestination")
  protected Double relaxTransitSearchGeneralizedCostAtDestination;

  @QueryParam("debugRaptorStops")
  private String debugRaptorStops;

  @QueryParam("debugRaptorPath")
  private String debugRaptorPath;

  /**
   * This takes a RouteRequest as JSON and uses it as the default before applying other
   * parameters. This is intended for debugging only! The RouteRequest is an internal OTP
   * class and will change without notice. The JSON is the same as the one in the
   * router-config for the "routingDefaults" parameter.
   */
  @QueryParam("config")
  private String config;

  /**
   * somewhat ugly bug fix: the graphService is only needed here for fetching per-graph time zones.
   * this should ideally be done when setting the routing context, but at present departure/ arrival
   * time is stored in the request as an epoch time with the TZ already resolved, and other code
   * depends on this behavior. (AMB) Alternatively, we could eliminate the separate RouteRequest
   * objects and just resolve vertices and timezones here right away, but just ignore them in
   * semantic equality checks.
   */
  @Context
  protected OtpServerRequestContext serverContext;

  /**
   * Range/sanity check the query parameter fields and build a Request object from them.
   *
   * @param queryParameters incoming request parameters
   */
  protected RouteRequest buildRequest(MultivaluedMap<String, String> queryParameters) {
    final RouteRequest request = defaultRouteRequest();

    // The routing request should already contain defaults, which are set when it is initialized or
    // in the JSON router configuration and cloned. We check whether each parameter was supplied
    // before overwriting the default.
    setIfNotNull(fromPlace, it -> request.setFrom(fromOldStyleString(it)));
    setIfNotNull(toPlace, it -> request.setTo(fromOldStyleString(it)));

    {
      //FIXME: move into setter method on routing request
      ZoneId tz = ZoneIdFallback.zoneId(serverContext.transitService().getTimeZone());
      if (date == null && time != null) { // Time was provided but not date
        LOG.debug("parsing ISO datetime {}", time);
        try {
          // If the time query param doesn't specify a timezone, use the graph's default. See issue #1373.
          DatatypeFactory df = javax.xml.datatype.DatatypeFactory.newInstance();
          XMLGregorianCalendar xmlGregCal = df.newXMLGregorianCalendar(time);
          ZonedDateTime dateTime = xmlGregCal.toGregorianCalendar().toZonedDateTime();
          if (xmlGregCal.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
            dateTime = dateTime.withZoneSameLocal(tz);
          }
          request.setDateTime(dateTime.toInstant());
        } catch (DatatypeConfigurationException e) {
          request.setDateTime(date, time, tz);
        }
      } else {
        request.setDateTime(date, time, tz);
      }
    }

    final Duration swDuration = DurationUtils.parseSecondsOrDuration(searchWindow).orElse(null);
    setIfNotNull(searchWindow, it -> request.setSearchWindow(swDuration));
    setIfNotNull(pageCursor, request::setPageCursorFromEncoded);
    setIfNotNull(timetableView, request::setTimetableView);
    setIfNotNull(wheelchair, request::setWheelchair);
    setIfNotNull(numItineraries, request::setNumItineraries);

    {
      var journey = request.journey();
      /* Temporary code to get bike/car parking and renting working. */
      if (modes != null && !modes.qModes.isEmpty()) {
        journey.setModes(modes.getRequestModes());
      }

      setIfNotNull(arriveBy, request::setArriveBy);

      {
        var transit = journey.transit();
        var filterBuilder = TransitFilterRequest.of();
        // Filter Agencies
        setIfNotNull(preferredAgencies, transit::setPreferredAgenciesFromString);
        setIfNotNull(unpreferredAgencies, transit::setUnpreferredAgenciesFromString);

        // Filter Routes
        setIfNotNull(preferredRoutes, transit::setPreferredRoutesFromString);
        setIfNotNull(unpreferredRoutes, transit::setUnpreferredRoutesFromString);

        // Filter Trips
        setIfNotNull(bannedTrips, journey.transit()::setBannedTripsFromString);

        // Excluded entities
        setIfNotNull(
          bannedAgencies,
          s -> filterBuilder.addNot(SelectRequest.of().withAgenciesFromString(s).build())
        );

        setIfNotNull(
          bannedRoutes,
          s -> filterBuilder.addNot(SelectRequest.of().withRoutesFromString(s).build())
        );

        // Included entities
        var selectors = new ArrayList<SelectRequest.Builder>();

        setIfNotNull(
          whiteListedAgencies,
          s -> selectors.add(SelectRequest.of().withAgenciesFromString(s))
        );

        setIfNotNull(
          whiteListedRoutes,
          s -> selectors.add(SelectRequest.of().withRoutesFromString(s))
        );

        List<MainAndSubMode> tModes;
        if (modes == null) {
          tModes = MainAndSubMode.all();
        } else {
          // Create modes
          tModes = modes.getTransitModes().stream().map(MainAndSubMode::new).toList();
        }

        // Add modes filter to all existing selectors
        // If no selectors specified, create new one
        if (!selectors.isEmpty()) {
          for (var selector : selectors) {
            filterBuilder.addSelect(selector.withTransportModes(tModes).build());
          }
        } else {
          filterBuilder.addSelect(SelectRequest.of().withTransportModes(tModes).build());
        }

        if (tModes.isEmpty()) {
          transit.disable();
        } else {
          transit.setFilters(List.of(filterBuilder.build()));
        }
      }
      {
        var debugRaptor = journey.transit().raptorDebugging();
        setIfNotNull(debugRaptorStops, debugRaptor::withStops);
        setIfNotNull(debugRaptorPath, debugRaptor::withPath);
      }
    }

    if (locale != null) {
      request.setLocale(Locale.forLanguageTag(locale.replaceAll("-", "_")));
    }

    request.withPreferences(preferences -> {
      // Map all preferences, note dependency on 'isTripPlannedForNow'.
      new RequestToPreferencesMapper(this, preferences, request.isTripPlannedForNow()).map();

      if (OTPFeature.DataOverlay.isOn()) {
        var dataOverlayParameters = DataOverlayParameters.parseQueryParams(queryParameters);
        if (!dataOverlayParameters.isEmpty()) {
          preferences.withSystem(it -> it.withDataOverlay(dataOverlayParameters));
        }
      }
    });
    return request;
  }

  /**
   * This method returns the configured default routing request with modifications passed in by the
   * `config` parameter. Only if {@code OTPFeature.RestAPIPAssInDefaultConfigAsJson.isOn()}.
   */
  private RouteRequest defaultRouteRequest() {
    RouteRequest request = serverContext.defaultRouteRequest();

    if (OTPFeature.RestAPIPassInDefaultConfigAsJson.isOn() && StringUtils.hasValue(config)) {
      var source = "Request.config";
      var root = ConfigFileLoader.nodeFromString(config, source);
      return RouteRequestConfig.mapRouteRequest(new NodeAdapter(root, source), request);
    } else {
      return request;
    }
  }
}