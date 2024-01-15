package org.opentripplanner.inspector.vector.vertex;

import java.util.List;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.inspector.vector.LayerBuilder;
import org.opentripplanner.inspector.vector.LayerParameters;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.index.StreetIndex;
import org.opentripplanner.street.model.vertex.Vertex;

/**
 * Selects all vertices to be displayed for debugging.
 */
public class VertexLayerBuilder extends LayerBuilder<Vertex> {

  private final StreetIndex streetIndex;

  public VertexLayerBuilder(Graph graph, LayerParameters layerParameters) {
    super(new VertexPropertyMapper(), layerParameters.name(), layerParameters.expansionFactor());
    this.streetIndex = graph.getStreetIndex();
  }

  @Override
  protected List<Geometry> getGeometries(Envelope query) {
    return streetIndex
      .getEdgesForEnvelope(query)
      .stream()
      .filter(e -> e.getGeometry() != null)
      .map(edge -> {
        Geometry geometry = edge.getGeometry();
        geometry.setUserData(edge);
        return geometry;
      })
      .toList();
  }
}
