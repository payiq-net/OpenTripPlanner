package org.opentripplanner.transit.raptor.rangeraptor.transit;

import org.opentripplanner.routing.algorithm.raptor.transit.TripPatternForDate;
import org.opentripplanner.routing.algorithm.raptor.transit.request.TripPatternForDates;
import org.opentripplanner.routing.trippattern.FrequencyEntry;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.transit.raptor.api.transit.RaptorTimeTable;
import org.opentripplanner.transit.raptor.api.transit.RaptorTripSchedule;
import org.opentripplanner.transit.raptor.api.transit.RaptorTripScheduleBoardOrAlightEvent;

public final class TripFrequencyAlightSearch<T extends RaptorTripSchedule> implements TripScheduleSearch<T> {

    private final TripPatternForDates timeTable;

    public TripFrequencyAlightSearch(RaptorTimeTable<T> timeTable) {
        this.timeTable = (TripPatternForDates) timeTable;
    }

    @Override
    public RaptorTripScheduleBoardOrAlightEvent<T> search(
            int earliestBoardTime,
            int stopPositionInPattern,
            int tripIndexLimit
    ) {
        for (int i = timeTable.tripPatternForDates.length - 1; i >= 0; i--) {
            TripPatternForDate pattern = timeTable.tripPatternForDates[i];
            int offset = timeTable.offsets[i];

            for (int j = pattern.getFrequencies().size() - 1; j >= 0; j--) {
                final FrequencyEntry frequency = pattern.getFrequencies().get(j);
                var arrivalTime = frequency.prevArrivalTime(
                        stopPositionInPattern,
                        earliestBoardTime - offset
                );
                if (arrivalTime != -1) {
                    int headway = frequency.exactTimes ? 0 : frequency.headway;
                    TripTimes tripTimes = frequency.materialize(
                            stopPositionInPattern,
                            arrivalTime + headway,
                            false
                    );

                    return new FrequencyAlightEvent<>(
                            timeTable,
                            tripTimes,
                            pattern.getTripPattern().getPattern(),
                            stopPositionInPattern,
                            arrivalTime + headway,
                            headway,
                            offset,
                            pattern.getLocalDate()
                    );
                }
            }
        }
        return null;
    }
}