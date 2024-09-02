package org.opentripplanner.ext.siri.updater.google;

import java.util.function.Consumer;
import org.opentripplanner.ext.siri.SiriTimetableSnapshotSource;
import org.opentripplanner.ext.siri.updater.AsyncEstimatedTimetableProcessor;
import org.opentripplanner.ext.siri.updater.AsyncEstimatedTimetableSource;
import org.opentripplanner.ext.siri.updater.EstimatedTimetableHandler;
import org.opentripplanner.transit.service.DefaultTransitService;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.updater.spi.GraphUpdater;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.spi.WriteToGraphCallback;
import org.opentripplanner.updater.trip.metrics.TripUpdateMetrics;

/**
 * Graph updater that processes a SIRI-ET feed based on a Google Pubsub subscription. This class
 * configures a {@link GooglePubsubEstimatedTimetableSource} and an {@link EstimatedTimetableHandler}
 * and delegates the update process to {@link AsyncEstimatedTimetableProcessor}
 */
public class SiriETGooglePubsubUpdater implements GraphUpdater {

  private final String configRef;
  private final AsyncEstimatedTimetableSource asyncEstimatedTimetableSource;
  private final EstimatedTimetableHandler estimatedTimetableHandler;
  private final Consumer<UpdateResult> updateResultConsumer;
  private WriteToGraphCallback saveResultOnGraph;

  public SiriETGooglePubsubUpdater(
    SiriETGooglePubsubUpdaterParameters config,
    TransitModel transitModel,
    SiriTimetableSnapshotSource timetableSnapshotSource
  ) {
    configRef = config.configRef();

    asyncEstimatedTimetableSource =
      new GooglePubsubEstimatedTimetableSource(
        config.dataInitializationUrl(),
        config.reconnectPeriod(),
        config.initialGetDataTimeout(),
        config.subscriptionProjectName(),
        config.topicProjectName(),
        config.topicName()
      );

    estimatedTimetableHandler =
      new EstimatedTimetableHandler(
        timetableSnapshotSource,
        config.fuzzyTripMatching(),
        new DefaultTransitService(transitModel),
        config.feedId()
      );

    updateResultConsumer = TripUpdateMetrics.streaming(config);
  }

  @Override
  public void setup(WriteToGraphCallback writeToGraphCallback) {
    this.saveResultOnGraph = writeToGraphCallback;
  }

  @Override
  public void run() {
    AsyncEstimatedTimetableProcessor asyncEstimatedTimetableProcessor = new AsyncEstimatedTimetableProcessor(
      estimatedTimetableHandler,
      saveResultOnGraph,
      updateResultConsumer
    );
    asyncEstimatedTimetableSource.start(asyncEstimatedTimetableProcessor::processSiriData);
  }

  @Override
  public boolean isPrimed() {
    return asyncEstimatedTimetableSource.isPrimed();
  }

  @Override
  public String getConfigRef() {
    return configRef;
  }
}
