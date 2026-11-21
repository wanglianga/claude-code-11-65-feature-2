package com.agri.pit.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal GeoJSON helper. The local deployment intentionally avoids PostGIS so the
 * service can run on a stock PostgreSQL; geometries are stored as GeoJSON and parsed
 * with JTS for containment / intersection checks.
 */
@Component
public class GeoUtil {

    private final GeometryFactory geometryFactory = new GeometryFactory();

    public Geometry parse(JsonNode geojson) {
        if (geojson == null || geojson.isNull()) {
            throw ApiException.badRequest("GEOJSON_REQUIRED", "geometry GeoJSON is required");
        }
        String type = geojson.path("type").asText("");
        JsonNode coords = geojson.path("coordinates");
        switch (type) {
            case "Polygon" -> {
                return polygon(coords);
            }
            case "MultiPolygon" -> {
                List<Polygon> polygons = new ArrayList<>();
                for (JsonNode poly : coords) {
                    polygons.add(polygon(poly));
                }
                return geometryFactory.createMultiPolygon(polygons.toArray(new Polygon[0]));
            }
            case "Point" -> {
                return geometryFactory.createPoint(toCoordinate(coords));
            }
            default -> throw ApiException.badRequest("UNSUPPORTED_GEOMETRY",
                    "unsupported GeoJSON geometry type: " + type);
        }
    }

    private Polygon polygon(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) {
            throw ApiException.badRequest("INVALID_GEOMETRY", "polygon must contain at least one ring");
        }
        LinearRing shell = ring(rings.get(0));
        LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring(rings.get(i));
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private LinearRing ring(JsonNode node) {
        List<Coordinate> coords = new ArrayList<>();
        for (JsonNode point : node) {
            coords.add(toCoordinate(point));
        }
        if (coords.size() < 4) {
            throw ApiException.badRequest("INVALID_GEOMETRY", "polygon ring needs at least 4 coordinates");
        }
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (!first.equals2D(last)) {
            coords.add(new Coordinate(first));
        }
        return geometryFactory.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    private Coordinate toCoordinate(JsonNode node) {
        if (!node.isArray() || node.size() < 2) {
            throw ApiException.badRequest("INVALID_GEOMETRY", "coordinate must be [lon, lat]");
        }
        double lon = node.get(0).asDouble();
        double lat = node.get(1).asDouble();
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            throw ApiException.badRequest("INVALID_GEOMETRY", "coordinate out of range: " + lon + "," + lat);
        }
        return new Coordinate(lon, lat);
    }

    /** Point-in-polygon test using the cell centroid. Boundary points count as inside. */
    public boolean contains(Geometry geometry, double lon, double lat) {
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        return geometry.contains(point) || geometry.covers(point);
    }

    public boolean intersects(Geometry a, Geometry b) {
        return a.intersects(b);
    }

    /**
     * Approximate geodesic area (hectares) by projecting degrees to local metres around
     * the geometry centroid. Adequate for field-sized polygons in the demo.
     */
    public double areaHectares(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return 0d;
        }
        Point centroid = geometry.getCentroid();
        double lat0 = centroid.getY();
        double metresPerDegLat = 111_320d;
        double metresPerDegLon = 111_320d * Math.cos(Math.toRadians(lat0));
        Geometry projected = transform(geometry, metresPerDegLon, metresPerDegLat);
        return projected.getArea() / 10_000d;
    }

    private Geometry transform(Geometry geometry, double sx, double sy) {
        Geometry copy = (Geometry) geometry.copy();
        copy.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                seq.setOrdinate(i, 0, seq.getOrdinate(i, 0) * sx);
                seq.setOrdinate(i, 1, seq.getOrdinate(i, 1) * sy);
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        });
        return copy;
    }

    public ObjectNode point(double lon, double lat) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "Point");
        ArrayNode coords = node.putArray("coordinates");
        coords.add(lon);
        coords.add(lat);
        return node;
    }

    /**
     * Serialize a JTS geometry back to GeoJSON. Only polygonal parts are emitted: overlap
     * intersections may carry degenerate line / point pieces, which carry no area and are dropped.
     * Returns null when the geometry has no polygonal component.
     */
    public ObjectNode toGeoJson(Geometry geometry) {
        ObjectMapper mapper = new ObjectMapper();
        if (geometry instanceof Polygon polygon) {
            return polygonNode(mapper, polygon);
        }
        if (geometry instanceof org.locationtech.jts.geom.MultiPolygon multi) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "MultiPolygon");
            ArrayNode coords = node.putArray("coordinates");
            for (int i = 0; i < multi.getNumGeometries(); i++) {
                coords.add(polygonCoordinates(mapper, (Polygon) multi.getGeometryN(i)));
            }
            return node;
        }
        List<Polygon> polygons = new ArrayList<>();
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry part = geometry.getGeometryN(i);
            if (part instanceof Polygon polygon && polygon.getArea() > 0) {
                polygons.add(polygon);
            }
        }
        if (polygons.isEmpty()) {
            return null;
        }
        if (polygons.size() == 1) {
            return polygonNode(mapper, polygons.get(0));
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "MultiPolygon");
        ArrayNode coords = node.putArray("coordinates");
        for (Polygon polygon : polygons) {
            coords.add(polygonCoordinates(mapper, polygon));
        }
        return node;
    }

    private ObjectNode polygonNode(ObjectMapper mapper, Polygon polygon) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "Polygon");
        node.set("coordinates", polygonCoordinates(mapper, polygon));
        return node;
    }

    private ArrayNode polygonCoordinates(ObjectMapper mapper, Polygon polygon) {
        ArrayNode rings = mapper.createArrayNode();
        rings.add(ringCoordinates(mapper, polygon.getExteriorRing().getCoordinates()));
        for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
            rings.add(ringCoordinates(mapper, polygon.getInteriorRingN(h).getCoordinates()));
        }
        return rings;
    }

    private ArrayNode ringCoordinates(ObjectMapper mapper, Coordinate[] coordinates) {
        ArrayNode ring = mapper.createArrayNode();
        for (Coordinate coordinate : coordinates) {
            ArrayNode point = ring.addArray();
            point.add(round6(coordinate.x));
            point.add(round6(coordinate.y));
        }
        return ring;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
