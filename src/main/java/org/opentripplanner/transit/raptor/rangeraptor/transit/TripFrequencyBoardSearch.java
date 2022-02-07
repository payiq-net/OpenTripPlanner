package org.opentripplanner.transit.raptor.rangeraptor.transit;

import org.opentripplanner.routing.algorithm.raptor.transit.TripPatternForDate;
import org.opentripplanner.routing.algorithm.raptor.transit.request.TripPatternForDates;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.transit.raptor.api.transit.RaptorTimeTable;
import org.opentripplanner.transit.raptor.api.transit.RaptorTripSchedule;
import org.opentripplanner.transit.raptor.api.transit.RaptorTripScheduleBoardOrAlightEvent;

public final class TripFrequencyBoardSearch<T extends RaptorTripSchedule> implements TripScheduleSearch<T> {

    private final TripPatternForDates timeTable;

    public TripFrequencyBoardSearch(RaptorTimeTable<T> timeTable) {
        this.timeTable = (TripPatternForDates) timeTable;
    }

    @Override
    public RaptorTripScheduleBoardOrAlightEvent<T> search(
            int earliestBoardTime,
            int stopPositionInPattern,
            int tripIndexLimit
    ) {
        for (int i = 0; i < timeTable.tripPatternForDates.length; i++) {
            TripPatternForDate pattern = timeTable.tripPatternForDates[i];
            int offset = timeTable.offsets[i];

            for (var frequency : pattern.getFrequencies()) {
                var departureTime = frequency.nextDepartureTime(stopPositionInPattern, earliestBoardTime - offset);
                if (departureTime != -1) {
                    int headway = frequency.exactTimes ? 0 : frequency.headway;
                    TripTimes tripTimes = frequency.materialize(
                            stopPositionInPattern,
                            departureTime - headway,
                            true
                    );

                    return new FrequencyBoardingEvent<>(
                            timeTable,
                            tripTimes,
                            pattern.getTripPattern().getPattern(),
                            stopPositionInPattern,
                            departureTime - headway,
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