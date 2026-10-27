package com.djavafactory.castor.core.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.layout.domain.AssetDetails;
import com.djavafactory.castor.core.layout.domain.AssetType;
import com.djavafactory.castor.core.layout.domain.AssetType.Flags;
import com.djavafactory.castor.core.layout.domain.AssetType.Sizes;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;

/** Shapes for the rule tests, written in local quay metres (x along the quay, y inland; water is y &lt; 0). */
public final class ShapeFixtures {

    private ShapeFixtures() {}

    public static Geometry wkt(String text) {
        try {
            Geometry g = new WKTReader().read(text);
            g.setSRID(4326);
            return g;
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static Geometry rect(double x0, double y0, double x1, double y1) {
        return wkt(LayoutTestSupport.localRect(x0, y0, x1, y1));
    }

    public static Geometry line(double x0, double y0, double x1, double y1) {
        return wkt(LayoutTestSupport.localLine(x0, y0, x1, y1));
    }

    /** A polygon from local points (the ring is closed here). */
    public static Geometry polygon(double... xy) {
        var sb = new StringBuilder("POLYGON((");
        for (int i = 0; i <= xy.length; i += 2) {
            int k = i % xy.length;
            var p = LayoutTestSupport.FRAME.toLonLat(xy[k], xy[k + 1]);
            sb.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.7f %.7f", p.lon(), p.lat()));
        }
        return wkt(sb.append("))").toString());
    }

    /** A line from local points. */
    public static Geometry path(double... xy) {
        var sb = new StringBuilder("LINESTRING(");
        for (int i = 0; i < xy.length; i += 2) {
            var p = LayoutTestSupport.FRAME.toLonLat(xy[i], xy[i + 1]);
            sb.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.7f %.7f", p.lon(), p.lat()));
        }
        return wkt(sb.append(")").toString());
    }

    /** An asset type; flags use the letters of the registry: S solid, F flat, Z zone, M mobile, W water, B berth. */
    public static AssetType type(String code, String kind, String mode, String flags) {
        var f = new Flags(
                flags.contains("M"),
                flags.contains("S"),
                flags.contains("F"),
                flags.contains("Z"),
                flags.contains("W"),
                flags.contains("B"));
        var none = new Sizes(null, null, null, null, null);
        var min = new Sizes(
                BigDecimal.valueOf(0.5),
                BigDecimal.valueOf(0.5),
                BigDecimal.valueOf(0.1),
                BigDecimal.ONE,
                BigDecimal.valueOf(0.1));
        return new AssetType(
                code, code, "G", "C", kind, mode, f, none, min, none, null, null, Map.of(), code, 1, "#000000", "", 1);
    }

    public static AssetType withMax(AssetType t, Sizes max) {
        return new AssetType(
                t.code(),
                t.label(),
                t.paletteGroup(),
                t.category(),
                t.geometryKind(),
                t.placementMode(),
                t.flags(),
                t.defaults(),
                t.min(),
                max,
                null,
                null,
                Map.of(),
                t.modelKey(),
                1,
                t.colourHex(),
                "",
                1);
    }

    /** Builds a LayoutAsset field by field; defaults describe an operator-placed shape with no details. */
    public static final class Asset {
        private final UUID id;
        private final String type;
        private final String name;
        private final Geometry geom;
        private BigDecimal length = BigDecimal.valueOf(10);
        private BigDecimal width = BigDecimal.valueOf(10);
        private BigDecimal height;
        private BigDecimal radius;
        private BigDecimal lineWidth;
        private LayoutAsset.Provenance provenance = new LayoutAsset.Provenance("OPERATOR", "OPERATOR", null, null);
        private LayoutAsset.Override override;
        private AssetDetails details = AssetDetails.NONE;

        private Asset(UUID id, String type, String name, Geometry geom) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.geom = geom;
        }

        public static Asset of(UUID id, String type, String name, Geometry geom) {
            return new Asset(id, type, name, geom);
        }

        public Asset size(Double length, Double width) {
            this.length = length == null ? null : BigDecimal.valueOf(length);
            this.width = width == null ? null : BigDecimal.valueOf(width);
            return this;
        }

        public Asset radius(double radius) {
            this.radius = BigDecimal.valueOf(radius);
            return this;
        }

        public Asset lineWidth(Double lineWidth) {
            this.lineWidth = lineWidth == null ? null : BigDecimal.valueOf(lineWidth);
            return this;
        }

        public Asset height(Double height) {
            this.height = height == null ? null : BigDecimal.valueOf(height);
            return this;
        }

        public Asset source(String source, String accuracy) {
            this.provenance = new LayoutAsset.Provenance(source, accuracy, null, null);
            return this;
        }

        public Asset override(String reason) {
            this.override = new LayoutAsset.Override(reason, "alice", Instant.now());
            return this;
        }

        public Asset details(AssetDetails details) {
            this.details = details;
            return this;
        }

        public LayoutAsset buildAs(UUID otherId) {
            return buildAs(otherId, name);
        }

        public LayoutAsset buildAs(UUID otherId, String otherName) {
            return Asset.of(otherId, type, otherName, geom)
                    .size(length == null ? null : length.doubleValue(), width == null ? null : width.doubleValue())
                    .withProvenance(provenance)
                    .build();
        }

        private Asset withProvenance(LayoutAsset.Provenance p) {
            this.provenance = p;
            return this;
        }

        public LayoutAsset build() {
            var centre = geom.getCentroid();
            var anchor = new LonLat(centre.getX(), centre.getY());
            return new LayoutAsset(
                    id,
                    name.toUpperCase(Locale.ROOT).replace(' ', '-'),
                    type,
                    name,
                    geom,
                    anchor,
                    null,
                    length,
                    width,
                    height,
                    radius,
                    lineWidth,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null,
                    null,
                    null,
                    provenance,
                    override,
                    details,
                    List.of(),
                    0);
        }
    }
}
