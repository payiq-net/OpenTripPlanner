package org.opentripplanner.standalone.config.sandbox;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.NA;

import org.opentripplanner.ext.dataoverlay.api.DataOverlayParameters;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;

public class DataOverlayParametersMapper {

  public static DataOverlayParameters map(NodeAdapter c) {
    var dataOverlay = new DataOverlayParameters();

    for (String param : DataOverlayParameters.parametersAsString()) {
      c
        .of(param)
        .withDoc(NA, /*TODO DOC*/"TODO")
        .asDoubleOptional()
        .ifPresent(it -> dataOverlay.put(param, it));
    }
    return dataOverlay;
  }
}
