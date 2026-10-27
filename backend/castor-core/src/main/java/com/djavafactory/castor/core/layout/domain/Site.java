package com.djavafactory.castor.core.layout.domain;

import org.locationtech.jts.geom.Geometry;

import com.djavafactory.castor.core.geometry.domain.QuayFrame;

/**
 * Where the layout is drawn: the quay frame and the port outlines in WGS84. Land extent and quay line are null for a
 * port that has none, and the rules that need them are then skipped.
 */
public record Site(QuayFrame frame, Geometry extent, Geometry landExtent, Geometry quayLine) {}
