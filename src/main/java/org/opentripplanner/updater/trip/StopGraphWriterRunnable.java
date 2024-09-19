package org.opentripplanner.updater.trip;

import com.google.transit.realtime.GtfsRealtime;
import java.util.List;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.opentripplanner.updater.RealTimeUpdateContext;

class StopGraphWriterRunnable implements GraphWriterRunnable {

  private final List<GtfsRealtime.Stop> updates;
  private final String feedId;

  StopGraphWriterRunnable(List<GtfsRealtime.Stop> updates, String feedId) {
    this.updates = updates;
    this.feedId = feedId;
  }

  @Override
  public void run(RealTimeUpdateContext context) {
    for (GtfsRealtime.Stop update : updates) {
      var stop = context
        .graph()
        .getStopVertexForStopId(new FeedScopedId(feedId, update.getStopId()));
      if (stop != null && update.hasStreetToStopTime()) {
        stop.setStreetToStopTime(update.getStreetToStopTime());
      }
    }
  }
}
